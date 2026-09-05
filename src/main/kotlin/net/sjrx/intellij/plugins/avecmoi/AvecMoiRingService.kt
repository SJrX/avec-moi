package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project

/**
 * Per-project store of the current handoff ring: the ordered spots Claude left for the human to
 * implement. Claude fills it via `register_handoffs`; navigation actions walk it.
 *
 * Each handoff is anchored by a [RangeMarker] so its line follows the human's edits (implementing
 * one handoff shifts the others down — raw line numbers would rot). Falls back to the registered
 * line if the file/document can't be resolved. Just data + a circular cursor — no policy.
 */
@Service(Service.Level.PROJECT)
class AvecMoiRingService(private val project: Project) : Disposable {

  /** Raw registration input from the MCP tool. */
  data class HandoffSpec(val file: String, val line: Int, val note: String)

  /** A live handoff: a RangeMarker that tracks edits, with the registered line as a fallback. */
  class Handoff(
    val file: String,
    val note: String,
    val marker: RangeMarker?,
    private val fallbackLine: Int,
  ) {
    /** Current 1-based line, tracking edits via the marker when it's still valid. */
    val line: Int
      get() {
        val m = marker
        return if (m != null && m.isValid) m.document.getLineNumber(m.startOffset) + 1 else fallbackLine
      }
  }

  private val handoffs = mutableListOf<Handoff>()
  private var cursor = -1

  fun set(specs: List<HandoffSpec>) {
    // Resolve files + create markers under a read lock (register_handoffs runs off the EDT).
    val built = ReadAction.compute<List<Handoff>, RuntimeException> {
      specs.map { spec -> Handoff(spec.file, spec.note, createMarker(spec.file, spec.line), spec.line) }
    }
    synchronized(this) {
      handoffs.forEach { it.marker?.dispose() }
      handoffs.clear()
      handoffs.addAll(built)
      cursor = -1
    }
  }

  @Synchronized
  fun isEmpty(): Boolean = handoffs.isEmpty()

  @Synchronized
  fun size(): Int = handoffs.size

  /** Advance the circular cursor and return the next handoff, or null if the ring is empty. */
  @Synchronized
  fun next(): Handoff? {
    if (handoffs.isEmpty()) return null
    cursor = (cursor + 1) % handoffs.size
    return handoffs[cursor]
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
      handoffs.forEach { it.marker?.dispose() }
      handoffs.clear()
    }
  }
}
