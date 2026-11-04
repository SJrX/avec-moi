package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl

/**
 * A handoff survives its text being deleted and brought back (#41): select all, delete, undo must
 * leave the handoff on its own line with its note showing again. Driven through the real editor
 * actions and undo stack of a light fixture, with the ring's edit tracking and the inlay service on.
 */
class AvecMoiHandoffReanchorTest : AbstractAvecMoiTest() {

  // Handoff files are resolved on the real disk, so keep this fixture's files there, not in memory.
  override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

  private val source = """
    fun a() {}
    fun b() {}
    fun c() {}
    fun partition() {}
    fun d() {}

  """.trimIndent()

  /** Open [source] with a handoff on line 4 (partition), its note drawn, and edit tracking on. */
  private fun openHandoffOnLine4(): AvecMoiRingService.Handoff {
    myFixture.configureByText("Partition.kt", source)
    ring.startTracking()
    project.service<AvecMoiInlayService>().start()
    ring.set(listOf(spec(file = myFixture.file.virtualFile.path, line = 4, note = "implement partition")))
    settle()
    return ring.all().single()
  }

  /** Let the re-anchor's deferred redraw run. */
  private fun settle() = PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

  private fun lineOf(h: AvecMoiRingService.Handoff) = h.line

  /** 1-based lines that have a note inlay above them. */
  private fun noteLines(): List<Int> {
    val doc = myFixture.editor.document
    return myFixture.editor.inlayModel.getBlockElementsInRange(0, doc.textLength).map { doc.getLineNumber(it.offset) + 1 }
  }

  private fun deleteAll() {
    myFixture.performEditorAction(IdeActions.ACTION_SELECT_ALL)
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_DELETE)
    settle()
  }

  private fun undo() {
    myFixture.performEditorAction(IdeActions.ACTION_UNDO)
    settle()
  }

  fun testSelectAllDeleteThenUndoPutsTheHandoffAndItsNoteBackOnItsLine() {
    // Fixture Setup
    val h = openHandoffOnLine4()
    assertEquals(listOf(4), noteLines())

    // Exercise SUT
    deleteAll()
    undo()

    // Verification
    assertEquals(source, myFixture.editor.document.text)
    assertEquals(4, lineOf(h))
    assertTrue("re-anchored", h.marker?.isValid == true)
    assertEquals(listOf(4), noteLines())
  }

  fun testOnceReAnchoredTheHandoffFollowsEditsAgain() {
    // Fixture Setup
    val h = openHandoffOnLine4()
    deleteAll()
    undo()

    // Exercise SUT — add two lines at the top
    WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "// one\n// two\n") }
    settle()

    // Verification
    assertEquals(6, lineOf(h))
  }

  fun testWhileItsTextIsGoneTheHandoffReportsWhereItWasNotWhereItWasRegistered() {
    // Fixture Setup — registered on 4, then two lines added above, so it's on 6
    val h = openHandoffOnLine4()
    WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "// one\n// two\n") }
    assertEquals(6, lineOf(h))

    // Exercise SUT
    deleteAll()

    // Verification
    assertEquals(6, lineOf(h))
  }

  fun testDeletingJustTheHandoffsLineThenUndoingKeepsItOnThatLine() {
    // Fixture Setup
    val h = openHandoffOnLine4()

    // Exercise SUT — delete lines 3–5 (c, partition, d) in one edit, then undo it
    val doc = myFixture.editor.document
    WriteCommandAction.runWriteCommandAction(project, "Delete lines", null, {
      doc.deleteString(doc.getLineStartOffset(2), doc.getLineEndOffset(4) + 1)
    })
    settle()
    undo()

    // Verification — back on partition, not pushed down by the restored lines above it
    assertEquals(source, doc.text)
    assertEquals(4, lineOf(h))
    assertEquals(listOf(4), noteLines())
  }

  fun testTypingIntoTheEmptiedFileAnchorsTheHandoffToTheLastLine() {
    // Fixture Setup
    val h = openHandoffOnLine4()
    deleteAll()

    // Exercise SUT
    myFixture.type("fun fresh() {}")
    settle()

    // Verification — the file only has one line now, so that's where it lands
    assertEquals(1, lineOf(h))
    assertTrue("re-anchored", h.marker?.isValid == true)
  }
}
