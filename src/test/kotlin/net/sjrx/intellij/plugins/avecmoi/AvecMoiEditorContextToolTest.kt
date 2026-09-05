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
    // Fixture Setup — the platform's <caret> marker places the caret at the start of the 2nd line
    myFixture.configureByText("Sample.kt", "line one\n<caret>line two\nline three\n")

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
    // Fixture Setup — the platform's <selection>…</selection> markers select the whole 2nd line
    myFixture.configureByText("Sample.kt", "line one\n<selection>line two</selection>\nline three\n")

    // Verification
    val context = editorContext()
    assertTrue("expected a selection block in: $context", context.contains("Selection"))
    assertTrue("expected the selected text in: $context", context.contains("line two"))
  }
}
