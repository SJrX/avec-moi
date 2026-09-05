package net.sjrx.intellij.plugins.avecmoi

/**
 * `get_editor_context` — how the agent sees what the human is looking at. Drives a real editor in the
 * fixture (caret + selection) and checks the reported context, including the 1-based line/column
 * conversion and the selection vs no-selection branches.
 */
class AvecMoiEditorContextToolTest : AbstractAvecMoiTest() {

  private fun editorContext(): String =
    callTool("get_editor_context", """{ "projectPath": "$projectPath" }""").contentText()

  fun testReportsTheCaretLineAndColumnOneBased() {
    // Fixture Setup — caret at the start of the 2nd line (0-based line 1)
    myFixture.configureByText("Sample.kt", "line one\nline two\nline three\n")
    myFixture.editor.caretModel.moveToOffset("line one\n".length)

    // Verification
    val context = editorContext()
    assertTrue("expected line 2 in: $context", context.contains("line 2"))
    assertTrue("expected column 1 in: $context", context.contains("column 1"))
    assertTrue(context.contains("No selection"))
  }

  fun testReportsNoActiveEditorWhenNothingIsOpen() {
    // Fixture Setup — no file configured, so there's no selected editor

    // Verification — a clean message, not an error or a crash
    assertTrue(editorContext().contains("No active editor"))
  }

  fun testReportsTheSelectedTextAndItsLineRange() {
    // Fixture Setup — select the whole 2nd line
    myFixture.configureByText("Sample.kt", "line one\nline two\nline three\n")
    val start = "line one\n".length
    val end = "line one\nline two".length
    myFixture.editor.selectionModel.setSelection(start, end)

    // Verification
    val context = editorContext()
    assertTrue("expected a selection block in: $context", context.contains("Selection"))
    assertTrue("expected the selected text in: $context", context.contains("line two"))
  }
}
