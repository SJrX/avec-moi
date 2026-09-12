package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.annotations.XCollection

/** Fired (on the publisher's thread) whenever the sessions/handoffs change. */
fun interface AvecMoiRingListener {
  fun ringChanged()
}

/**
 * What a handoff IS.
 * - [TODO]: a spot for the human to implement (the default).
 * - [READ]: a sequenced step to read/understand specific code as context — e.g. an implementation to
 *   mirror before the next to-do. A work item like a to-do (done-able, counted, stepped through),
 *   just one whose output is understanding rather than code.
 * - [REFERENCE]: an unordered, session-long bookmark to important code — "handy whenever". NOT a work
 *   item: its own list + index namespace + tree folder, no done-state, not counted, not stepped.
 *
 * TODO and READ live in the WORK list (one namespace); REFERENCE lives in its own.
 */
enum class HandoffKind(val label: String) {
  TODO("To do"),
  READ("Read"),
  REFERENCE("Reference");

  /** References are the odd one out — a separate namespace, not a completable work item. */
  val isReference: Boolean get() = this == REFERENCE

  companion object {
    /** Parse an agent-supplied kind, case-insensitively; unknown → [TODO]. */
    fun fromWire(value: String?): HandoffKind =
      entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: TODO
  }
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
@State(name = "AvecMoiSessions", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class AvecMoiRingService(private val project: Project) :
  PersistentStateComponent<AvecMoiRingService.PersistedState>, Disposable {

  data class HandoffSpec(val file: String, val line: Int, val note: String, val kind: HandoffKind = HandoffKind.TODO)

  class Handoff(
    val file: String,
    var note: String,
    var marker: RangeMarker?,
    private val fallbackLine: Int,
    val kind: HandoffKind = HandoffKind.TODO,
  ) {
    var done: Boolean = false

    /**
     * Current 1-based line, tracking edits via the marker when it's still valid. Reads the marker's
     * document, which needs a read action, so the getter self-guards — [runReadActionBlocking] is
     * reentrant, so callers already inside a read action pay nothing extra.
     */
    val line: Int
      get() = runReadActionBlocking {
        val m = marker
        if (m != null && m.isValid) m.document.getLineNumber(m.startOffset) + 1 else fallbackLine
      }
  }

  class Session(val id: String, var name: String, val branch: String?) {
    var historical: Boolean = false
    val handoffs = mutableListOf<Handoff>() // work items: TODO + READ (one index namespace)
    val references = mutableListOf<Handoff>() // REFERENCE bookmarks (their own index namespace)
    var cursor: Int = -1 // into handoffs (work); references aren't navigated

    /** The work list or the references list, per [reference]. */
    fun list(reference: Boolean): MutableList<Handoff> = if (reference) references else handoffs
  }

  private val sessions = mutableListOf<Session>()
  private var activeId: String? = null
  private var seq: Int = 0

  /** Whether an agent tool call has targeted this project since it was opened (runtime, not persisted). */
  @Volatile
  private var contacted: Boolean = false

  fun recordContact() {
    contacted = true
  }

  fun isContacted(): Boolean = contacted

  /** Clear the runtime contact flag so a test can observe a clean false→true (the project is reused). */
  @org.jetbrains.annotations.TestOnly
  fun resetContactForTest() {
    contacted = false
  }

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

  /** Remove a session entirely, disposing its handoff markers. Used to prune historical sessions. */
  fun deleteSession(id: String): Boolean {
    val ok = synchronized(this) {
      val s = sessions.firstOrNull { it.id == id } ?: return@synchronized false
      (s.handoffs + s.references).forEach { it.marker?.dispose() }
      sessions.remove(s)
      // A deleted session can't remain the active one; fall back to another open session (or none).
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

  /** Replace BOTH the work list and the references, partitioning [specs] by kind. */
  fun set(specs: List<HandoffSpec>) {
    val built = buildHandoffs(specs)
    synchronized(this) {
      val s = ensureActiveLocked()
      (s.handoffs + s.references).forEach { it.marker?.dispose() }
      s.handoffs.clear()
      s.references.clear()
      built.forEach { s.list(it.kind.isReference).add(it) }
      s.cursor = -1
    }
    changed()
  }

  /** Append [spec] to its namespace (work vs references, by kind). Returns its 1-based index there. */
  fun add(spec: HandoffSpec, at: Int?): Int {
    val handoff = buildHandoffs(listOf(spec)).first()
    val index = synchronized(this) {
      val s = ensureActiveLocked()
      val target = s.list(handoff.kind.isReference)
      val pos = at?.coerceIn(0, target.size) ?: target.size
      target.add(pos, handoff)
      pos
    }
    changed()
    return index + 1
  }

  /**
   * Edit an existing handoff in place, preserving its position and done-state. Only non-null fields
   * change. If [file] or [line] changes, the RangeMarker is re-anchored; a note-only edit keeps it.
   */
  fun update(index: Int, note: String?, file: String?, line: Int?, reference: Boolean = false): Boolean {
    val ok = synchronized(this) {
      val s = activeLocked() ?: return@synchronized false
      val target = s.list(reference)
      val old = target.getOrNull(index) ?: return@synchronized false
      // old.line reads the marker's document, so take it under a read lock (we're off the EDT).
      val oldLine = runReadActionBlocking { old.line }
      val fileChanged = file != null && file != old.file
      val lineChanged = line != null && line != oldLine
      if (fileChanged || lineChanged) {
        old.marker?.dispose()
        val rebuilt = buildHandoffs(
          listOf(HandoffSpec(file ?: old.file, line ?: oldLine, note ?: old.note, old.kind)),
        ).first()
        rebuilt.done = old.done
        target[index] = rebuilt
      } else if (note != null) {
        old.note = note
      }
      true
    }
    if (ok) changed()
    return ok
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

  fun remove(index: Int, reference: Boolean = false): Boolean {
    val ok = synchronized(this) {
      val s = activeLocked() ?: return@synchronized false
      val target = s.list(reference)
      if (index !in target.indices) return@synchronized false
      // The removed item's index in the combined [work..., refs...] sequence the cursor lives in.
      val combinedIndex = if (reference) s.handoffs.size + index else index
      target.removeAt(index).marker?.dispose()
      // Keep the cursor on the same logical item: anything removed before it shifts it left by one.
      if (combinedIndex < s.cursor) s.cursor--
      val combinedSize = s.handoffs.size + s.references.size
      if (s.cursor >= combinedSize) s.cursor = combinedSize - 1
      true
    }
    if (ok) changed()
    return ok
  }

  /** Clear BOTH the work list and the references for the active session. */
  fun clear() {
    synchronized(this) {
      val s = activeLocked() ?: return@synchronized
      (s.handoffs + s.references).forEach { it.marker?.dispose() }
      s.handoffs.clear()
      s.references.clear()
      s.cursor = -1
    }
    changed()
  }

  @Synchronized fun isEmpty(): Boolean = activeLocked()?.handoffs?.isEmpty() ?: true

  @Synchronized fun size(): Int = activeLocked()?.handoffs?.size ?: 0

  /** The work handoffs (to-dos + reviews) of the active session. */
  @Synchronized fun all(): List<Handoff> = activeLocked()?.handoffs?.toList() ?: emptyList()

  /** The reference bookmarks of the active session (their own namespace). */
  @Synchronized fun references(): List<Handoff> = activeLocked()?.references?.toList() ?: emptyList()

  /**
   * The cursor as an index into the COMBINED sequence [work handoffs..., references...], or -1. Work
   * comes first so references iterate only after tasks; a reference index is >= all().size (which is
   * why DoThisStep's all().getOrNull(cursor) safely no-ops on a reference).
   */
  @Synchronized fun cursorIndex(): Int = activeLocked()?.cursor ?: -1

  /** The handoff the cursor points at (a work item OR a reference), or null. */
  @Synchronized fun currentHandoff(): Handoff? =
    activeLocked()?.let { (it.handoffs + it.references).getOrNull(it.cursor) }

  /** Point the cursor at [handoff] (by identity), e.g. because its row was selected. Cursor-only. */
  @Synchronized fun setCurrent(handoff: Handoff): Boolean {
    val s = activeLocked() ?: return false
    val idx = (s.handoffs + s.references).indexOfFirst { it === handoff }
    if (idx < 0) return false
    s.cursor = idx
    return true
  }

  @Synchronized
  fun next(): Handoff? = activeLocked()?.let { advance(it, dir = +1) }

  @Synchronized
  fun prev(): Handoff? = activeLocked()?.let { advance(it, dir = -1) }

  /**
   * Move the cursor to the next (dir = +1) / previous (dir = -1) thing worth visiting, over the
   * combined [work..., references...] sequence, wrapping. Priority: not-done work items (the tasks
   * left to do). Only once EVERY task is done do references become the cycle — so references iterate
   * after tasks. If there are no tasks and no references, fall back to any handoff. Returns it.
   */
  private fun advance(s: Session, dir: Int): Handoff? {
    val combined = s.handoffs + s.references
    if (combined.isEmpty()) return null
    val workUndone = s.handoffs.indices.filter { !s.handoffs[it].done } // combined indices (work is first)
    val refIndices = s.references.indices.map { s.handoffs.size + it }
    val candidates = when {
      workUndone.isNotEmpty() -> workUndone
      refIndices.isNotEmpty() -> refIndices
      else -> combined.indices.toList() // all done, no references: still navigate
    }
    val next = if (dir > 0) candidates.firstOrNull { it > s.cursor } ?: candidates.first()
    else candidates.lastOrNull { it < s.cursor } ?: candidates.last()
    s.cursor = next
    return combined[next]
  }

  // --- helpers ----------------------------------------------------------------------------------

  private fun buildHandoffs(specs: List<HandoffSpec>): List<Handoff> =
    runReadActionBlocking {
      specs.map { spec -> Handoff(spec.file, spec.note, createMarker(spec.file, spec.line), spec.line, spec.kind) }
    }

  private fun createMarker(file: String, line1Based: Int): RangeMarker? {
    val vf = AvecMoiNavigation.resolveFile(project, file) ?: return null
    val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return null
    if (doc.lineCount == 0) return null
    val line0 = (line1Based - 1).coerceIn(0, doc.lineCount - 1)
    return doc.createRangeMarker(doc.getLineStartOffset(line0), doc.getLineEndOffset(line0))
  }

  // --- persistence (workspace-level) ------------------------------------------------------------

  class PersistedState {
    @get:XCollection
    var sessions: MutableList<SessionState> = mutableListOf()
    var activeId: String? = null
    var seq: Int = 0
  }

  class SessionState {
    var id: String = ""
    var name: String = ""
    var branch: String? = null
    var historical: Boolean = false

    @get:XCollection
    var handoffs: MutableList<HandoffState> = mutableListOf()

    @get:XCollection
    var references: MutableList<HandoffState> = mutableListOf()
  }

  class HandoffState {
    var file: String = ""
    var line: Int = 1
    var note: String = ""
    var done: Boolean = false
    var kind: HandoffKind = HandoffKind.TODO
  }

  override fun getState(): PersistedState {
    val st = PersistedState()
    synchronized(this) {
      st.activeId = activeId
      st.seq = seq
      for (s in sessions) {
        val ss = SessionState()
        ss.id = s.id
        ss.name = s.name
        ss.branch = s.branch
        ss.historical = s.historical
        fun persist(h: Handoff) = HandoffState().also {
          it.file = h.file
          it.line = runReadActionBlocking { h.line }
          it.note = h.note
          it.done = h.done
          it.kind = h.kind
        }
        s.handoffs.forEach { ss.handoffs.add(persist(it)) }
        s.references.forEach { ss.references.add(persist(it)) }
        st.sessions.add(ss)
      }
    }
    return st
  }

  override fun loadState(state: PersistedState) {
    synchronized(this) {
      sessions.forEach { s -> (s.handoffs + s.references).forEach { it.marker?.dispose() } }
      sessions.clear()
      activeId = state.activeId
      seq = state.seq
      for (ss in state.sessions) {
        val s = Session(ss.id, ss.name, ss.branch)
        s.historical = ss.historical
        fun restore(hs: HandoffState) = Handoff(hs.file, hs.note, null, hs.line, hs.kind).also { it.done = hs.done }
        ss.handoffs.forEach { s.handoffs.add(restore(it)) }
        ss.references.forEach { s.references.add(restore(it)) }
        sessions.add(s)
      }
    }
  }

  /** Create RangeMarkers for loaded handoffs (documents may not be ready at loadState time). */
  fun materializeMarkers() {
    synchronized(this) {
      for (s in sessions) for (h in s.handoffs + s.references) {
        if (h.marker?.isValid != true) {
          h.marker = runReadActionBlocking { createMarker(h.file, h.line) }
        }
      }
    }
    changed()
  }

  override fun dispose() {
    synchronized(this) {
      sessions.forEach { s -> (s.handoffs + s.references).forEach { it.marker?.dispose() } }
      sessions.clear()
    }
  }

  companion object {
    @JvmField
    val TOPIC: Topic<AvecMoiRingListener> = Topic.create("avec-moi ring changed", AvecMoiRingListener::class.java)
  }
}
