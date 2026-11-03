package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl

/**
 * The phrasebook in the editor: its own block inlay directly under the handoff's note, folded
 * independently of it. Drives the real inlay service against a real light-fixture editor and reads
 * back the block inlays above the handoff's line, top to bottom.
 */
class AvecMoiPhrasebookInlayTest : AbstractAvecMoiTest() {

  private val inlays get() = project.service<AvecMoiInlayService>()

  // Handoff files are resolved on the real disk, so keep this fixture's files there, not in memory.
  override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

  /** The kinds of inlay floating above line 2 (1-based), top to bottom: "note" / "phrasebook". */
  private fun inlaysAboveLine2(): List<String> =
    myFixture.editor.inlayModel.getBlockElementsForVisualLine(1, true).map {
      when (it.renderer.javaClass.simpleName) {
        "NoteInlayRenderer" -> "note"
        "PhrasebookInlayRenderer" -> "phrasebook"
        else -> it.renderer.javaClass.simpleName
      }
    }

  /** Open a file with a handoff on line 2, with or without a phrasebook, and render its inlays. */
  private fun openHandoff(phrases: List<Phrase>): AvecMoiRingService.Handoff {
    myFixture.configureByText("Partition.kt", "fun a() {}\nfun partition() {}\n")
    ring.set(listOf(spec(file = myFixture.file.virtualFile.path, line = 2, note = "implement partition").copy(phrases = phrases)))
    inlays.refreshAll()
    return ring.all().single()
  }

  fun testThePhrasebookIsItsOwnInlayDirectlyUnderTheNote() {
    // Exercise SUT
    openHandoff(listOf(Phrase("a ?: b", "elvis")))

    // Verification
    assertEquals(listOf("note", "phrasebook"), inlaysAboveLine2())
  }

  fun testAHandoffWithoutPhrasesHasOnlyItsNote() {
    // Exercise SUT
    openHandoff(emptyList())

    // Verification
    assertEquals(listOf("note"), inlaysAboveLine2())
  }

  fun testFoldingThePhrasebookKeepsItAsAOneLineInlayAndLeavesTheNoteAlone() {
    // Fixture Setup
    val handoff = openHandoff(listOf(Phrase("a ?: b", "elvis"), Phrase("xs.map { it }")))
    val expandedHeight = phrasebookHeight()

    // Exercise SUT
    inlays.togglePhrasebookCollapsed(handoff)

    // Verification — still there, now just its heading line; the note is still shown
    assertEquals(listOf("note", "phrasebook"), inlaysAboveLine2())
    assertEquals(myFixture.editor.lineHeight, phrasebookHeight())
    assertTrue("folded must be shorter than expanded", phrasebookHeight() < expandedHeight)
  }

  fun testFoldingTheNotePutsThePhrasebookAwayToo() {
    // Fixture Setup
    val handoff = openHandoff(listOf(Phrase("a ?: b", "elvis")))

    // Exercise SUT
    inlays.toggleCollapsed(handoff)

    // Verification
    assertEquals(listOf("note"), inlaysAboveLine2())
  }

  private fun phrasebookHeight(): Int =
    myFixture.editor.inlayModel.getBlockElementsForVisualLine(1, true)
      .single { it.renderer.javaClass.simpleName == "PhrasebookInlayRenderer" }.heightInPixels
}
