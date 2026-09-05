package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.RangeMarker

/**
 * A handoff's reported line must track edits to the file (via its [RangeMarker]) and degrade sanely
 * when the anchor is gone. This is the "line anchors rot" risk: an agent registers line 42, the human
 * inserts code above it, and the IDE must still point at the right spot — not the stale number.
 */
class AvecMoiHandoffLineTrackingTest : AbstractAvecMoiTest() {

  private fun document(text: String): Document = EditorFactory.getInstance().createDocument(text)

  private fun markerAtLine(doc: Document, line0: Int): RangeMarker =
    doc.createRangeMarker(doc.getLineStartOffset(line0), doc.getLineEndOffset(line0))

  private fun handoff(marker: RangeMarker?, fallbackLine: Int) =
    AvecMoiRingService.Handoff("F.kt", "note", marker, fallbackLine)

  /** [AvecMoiRingService.Handoff.line] touches the marker's document, so read it under a read lock. */
  private fun lineOf(h: AvecMoiRingService.Handoff): Int =
    ReadAction.compute<Int, RuntimeException> { h.line }

  fun testLineReportsTheMarkersCurrentLineOneBased() {
    // Fixture Setup — mark the 2nd line (0-based index 1)
    val doc = document("alpha\nbeta\ngamma\n")
    val h = handoff(markerAtLine(doc, 1), fallbackLine = 99)

    // Verification
    assertEquals(2, lineOf(h))
  }

  fun testLineFollowsInsertionsMadeAboveTheHandoff() {
    // Fixture Setup — mark "gamma" (line 3)
    val doc = document("alpha\nbeta\ngamma\n")
    val h = handoff(markerAtLine(doc, 2), fallbackLine = 99)
    assertEquals(3, lineOf(h))

    // Exercise SUT — insert two lines at the very top
    WriteCommandAction.runWriteCommandAction(project) {
      doc.insertString(0, "one\ntwo\n")
    }

    // Verification — the handoff shifts down with its line, it does not stay at 3
    assertEquals(5, lineOf(h))
  }

  fun testLineFallsBackToTheStoredLineWhenTheMarkerIsInvalid() {
    // Fixture Setup
    val doc = document("alpha\nbeta\n")
    val marker = markerAtLine(doc, 1)
    val h = handoff(marker, fallbackLine = 42)

    // Exercise SUT — lose the anchor
    marker.dispose()

    // Verification — degrade to the last known line, not a crash
    assertEquals(42, lineOf(h))
  }

  fun testLineUsesTheFallbackWhenThereIsNoMarkerAtAll() {
    // (This is the state right after loadState, before materializeMarkers re-creates anchors.)
    val h = handoff(marker = null, fallbackLine = 7)
    assertEquals(7, lineOf(h))
  }
}
