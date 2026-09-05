package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/** Fired (on the publisher's thread) whenever the sessions/handoffs change. */
fun interface AvecMoiRingListener {
  fun ringChanged()
}

/**
 * Per-project store of avec-moi sessions. A session is a named container of handoffs; one session is
 * "active" (the one navigation and handoff tools operate on), the rest are open or historical.
 *
 * The ring-style methods (set/add/next/prev/markDone/…) act on the ACTIVE session, auto-creating one
 * if none exists — so a single-agent flow never has to think about sessions. Each handoff is anchored
 * by a [RangeMarker] so its line tracks edits.
 *
 * State is in-memory for now; workspace-level persistence is a follow-up.
 */
@Service(Service.Level.PROJECT)
class AvecMoiRingService(private val project: Project) : Disposable {

  data class HandoffSpec(val file: String, val line: Int, val note: String)

  class Handoff(
    val file: String,
    val note: String,
    val marker: RangeMarker?,
    private val fallbackLine: Int,
  ) {
    var done: Boolean = false

    /** Current 1-based line, tracking edits via the marker when it's still valid. */
    val line: Int
      get() {
        val m = marker
        return if (m != null && m.isValid) m.document.getLineNumber(m.startOffset) + 1 else fallbackLine
      }
  }

  class Session(val id: String, var name: String, val branch: String?) {
    var historical: Boolean = false
    val handoffs = mutableListOf<Handoff>()
    var cursor: Int = -1
  }

  private val sessions = mutableListOf<Session>()
  private var activeId: String? = null
  private var seq: Int = 0

  private fun changed() = project.messageBus.syncPublisher(TOPIC).ringChanged()

  // --- session management -----------------------------------------------------------------------

  fun createSession(name: String, branch: String?): String {
    val id = synchronized(this) {
      val newId = "s${++seq}"
      sessions.add(Session(newId, name, branch))
      activeId = newId
      newId
    }
    changed()
    return id
  }

  fun listSessions(): List<Session> = synchronized(this) { sessions.toList() }

  fun activeId(): String? = synchronized(this) { activeId }

  fun switchSession(id: String): Boolean {
    val ok = synchronized(this) {
      val s = sessions.firstOrNull { it.id == id && !it.historical } ?: return@synchronized false
      activeId = s.id
      true
    }
    if (ok) changed()
    return ok
  }

  fun renameSession(id: String, name: String): Boolean {
    val ok = synchronized(this) {
      val s = sessions.firstOrNull { it.id == id } ?: return@synchronized false
      s.name = name
      true
    }
    if (ok) changed()
    return ok
  }

  fun completeSession(id: String): Boolean {
    val ok = synchronized(this) {
      val s = sessions.firstOrNull { it.id == id } ?: return@synchronized false
      s.historical = true
      if (activeId == id) activeId = sessions.firstOrNull { !it.historical }?.id
      true
    }
    if (ok) changed()
    return ok
  }

  /** The active (non-historical) session, or null. Caller must hold the lock. */
  private fun activeLocked(): Session? =
    activeId?.let { id -> sessions.firstOrNull { it.id == id && !it.historical } }

  /** The active session, creating a default one if none. Caller must hold the lock. */
  private fun ensureActiveLocked(): Session {
    activeLocked()?.let { return it }
    val s = Session("s${++seq}", "Untitled session", null)
    sessions.add(s)
    activeId = s.id
    return s
  }

  // --- ring ops (operate on the ACTIVE session) -------------------------------------------------

  fun set(specs: List<HandoffSpec>) {
    val built = buildHandoffs(specs)
    synchronized(this) {
      val s = ensureActiveLocked()
      s.handoffs.forEach { it.marker?.dispose() }
      s.handoffs.clear()
      s.handoffs.addAll(built)
      s.cursor = -1
    }
    changed()
  }

  fun add(spec: HandoffSpec, at: Int?): Int {
    val handoff = buildHandoffs(listOf(spec)).first()
    val index = synchronized(this) {
      val s = ensureActiveLocked()
      val pos = at?.coerceIn(0, s.handoffs.size) ?: s.handoffs.size
      s.handoffs.add(pos, handoff)
      pos
    }
    changed()
    return index + 1
  }

  fun markDone(index: Int, done: Boolean): Boolean {
    val ok = synchronized(this) {
      val s = activeLocked() ?: return@synchronized false
      val h = s.handoffs.getOrNull(index) ?: return@synchronized false
      h.done = done
      true
    }
    if (ok) changed()
    return ok
  }

  fun remove(index: Int): Boolean {
    val ok = synchronized(this) {
      val s = activeLocked() ?: return@synchronized false
      if (index !in s.handoffs.indices) return@synchronized false
      s.handoffs.removeAt(index).marker?.dispose()
      if (s.cursor >= s.handoffs.size) s.cursor = s.handoffs.size - 1
      true
    }
    if (ok) changed()
    return ok
  }

  fun clear() {
    synchronized(this) {
      val s = activeLocked() ?: return@synchronized
      s.handoffs.forEach { it.marker?.dispose() }
      s.handoffs.clear()
      s.cursor = -1
    }
    changed()
  }

  @Synchronized fun isEmpty(): Boolean = activeLocked()?.handoffs?.isEmpty() ?: true

  @Synchronized fun size(): Int = activeLocked()?.handoffs?.size ?: 0

  @Synchronized fun all(): List<Handoff> = activeLocked()?.handoffs?.toList() ?: emptyList()

  @Synchronized fun cursorIndex(): Int = activeLocked()?.cursor ?: -1

  @Synchronized
  fun next(): Handoff? {
    val s = activeLocked() ?: return null
    if (s.handoffs.isEmpty()) return null
    s.cursor = (s.cursor + 1) % s.handoffs.size
    return s.handoffs[s.cursor]
  }

  @Synchronized
  fun prev(): Handoff? {
    val s = activeLocked() ?: return null
    if (s.handoffs.isEmpty()) return null
    s.cursor = if (s.cursor <= 0) s.handoffs.size - 1 else s.cursor - 1
    return s.handoffs[s.cursor]
  }

  // --- helpers ----------------------------------------------------------------------------------

  private fun buildHandoffs(specs: List<HandoffSpec>): List<Handoff> =
    ReadAction.compute<List<Handoff>, RuntimeException> {
      specs.map { spec -> Handoff(spec.file, spec.note, createMarker(spec.file, spec.line), spec.line) }
    }

  private fun createMarker(file: String, line1Based: Int): RangeMarker? {
    val vf = AvecMoiNavigation.resolveFile(project, file) ?: return null
    val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return null
    if (doc.lineCount == 0) return null
    val line0 = (line1Based - 1).coerceIn(0, doc.lineCount - 1)
    return doc.createRangeMarker(doc.getLineStartOffset(line0), doc.getLineEndOffset(line0))
  }

  override fun dispose() {
    synchronized(this) {
      sessions.forEach { s -> s.handoffs.forEach { it.marker?.dispose() } }
      sessions.clear()
    }
  }

  companion object {
    @JvmField
    val TOPIC: Topic<AvecMoiRingListener> = Topic.create("avec-moi ring changed", AvecMoiRingListener::class.java)
  }
}
