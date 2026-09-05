package net.sjrx.intellij.plugins.avecmoi

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The message "Ask for a Hint" sends the agent. Pure logic, but it carries two things that matter:
 * the correct location reference (so the agent looks where the human actually is), and the
 * "don't implement it for me" guardrail that keeps a hint a hint — the product's whole point.
 */
class AskForHintMessageTest {

  @Test
  fun aCaretHintReferencesTheFileAndLine() {
    val message = AskForHintAction.hintMessage("/p/A.kt", selectionLines = null, caretLine = 12)
    assertTrue("has file:line reference: $message", message.contains("/p/A.kt:12"))
    assertTrue("keeps the don't-implement guardrail: $message", message.contains("don't implement"))
  }

  @Test
  fun aSelectionHintReferencesTheLineRange() {
    val message = AskForHintAction.hintMessage("/p/A.kt", selectionLines = 5 to 9, caretLine = 0)
    assertTrue("has line-range reference: $message", message.contains("/p/A.kt lines 5-9"))
    assertTrue("keeps the don't-implement guardrail: $message", message.contains("don't implement"))
  }
}
