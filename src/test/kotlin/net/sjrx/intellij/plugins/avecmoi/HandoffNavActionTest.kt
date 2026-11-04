package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent

/**
 * Whether Next/Previous Handoff are enabled, through the real actions' update(). A session of only
 * references is still navigable (#43); a session with nothing in it isn't.
 */
class HandoffNavActionTest : AbstractAvecMoiTest() {

  private fun enabled(action: AnAction): Boolean {
    val event = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))
    action.update(event)
    return event.presentation.isEnabled
  }

  private fun reference(note: String) = spec(note = note).copy(kind = HandoffKind.REFERENCE)

  fun testNextAndPreviousAreEnabledForASessionOfOnlyReferences() {
    // Fixture Setup
    ring.set(listOf(reference("entry point"), reference("config")))

    // Exercise SUT + Verification
    assertTrue("next", enabled(NextHandoffAction()))
    assertTrue("previous", enabled(PreviousHandoffAction()))
  }

  fun testNextStepsThroughTheReferencesWhenThereIsNoWork() {
    // Fixture Setup
    ring.set(listOf(reference("entry point"), reference("config")))

    // Exercise SUT + Verification
    assertEquals("entry point", ring.next()?.note)
    assertEquals("config", ring.next()?.note)
  }

  fun testNextAndPreviousAreDisabledWhenTheSessionIsEmpty() {
    // Fixture Setup
    ring.set(emptyList())

    // Exercise SUT + Verification
    assertFalse("next", enabled(NextHandoffAction()))
    assertFalse("previous", enabled(PreviousHandoffAction()))
  }
}
