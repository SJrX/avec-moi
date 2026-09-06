package net.sjrx.intellij.plugins.avecmoi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The message "Do This Step" hands to the agent. Pure logic: the location reference (single line as
 * file:line, a selection as a line range) and the optional handoff note, all wrapped in the
 * "you implement this, not me" phrasing that defines the action.
 */
class DoThisStepMessageTest {

  @Test
  fun aSingleLineUsesFileColonLine() {
    val message = DoThisStepAction.stepMessage("/p/A.kt", startLine = 42, endLine = 42, note = null)
    assertTrue("has file:line: $message", message.contains("/p/A.kt:42"))
    assertFalse("should not say 'lines': $message", message.contains("lines"))
    assertTrue("keeps the implement-this-yourself intent: $message", message.contains("implement this step"))
  }

  @Test
  fun aMultiLineSelectionUsesALineRange() {
    val message = DoThisStepAction.stepMessage("/p/A.kt", startLine = 5, endLine = 9, note = null)
    assertTrue("has line range: $message", message.contains("/p/A.kt lines 5-9"))
  }

  @Test
  fun theHandoffNoteIsIncludedWhenPresent() {
    val message = DoThisStepAction.stepMessage("/p/A.kt", 42, 42, note = "parse the header")
    assertTrue("includes the note: $message", message.contains("parse the header"))
  }

  @Test
  fun aBlankNoteIsOmitted() {
    val message = DoThisStepAction.stepMessage("/p/A.kt", 42, 42, note = "   ")
    assertFalse("no dangling note separator: $message", message.contains("—"))
  }
}
