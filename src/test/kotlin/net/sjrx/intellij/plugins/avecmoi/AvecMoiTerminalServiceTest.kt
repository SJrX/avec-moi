package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.components.service

/**
 * The terminal "push" channel's enumeration + gating. We can't drive a real terminal in a unit test
 * (no PTY), but we CAN pin the no-terminal contract the quick-action buttons depend on: with nothing
 * open, there are no tabs, no target, and send() is a safe no-op rather than an NPE.
 */
class AvecMoiTerminalServiceTest : AbstractAvecMoiTest() {

  private val terminal get() = project.service<AvecMoiTerminalService>()

  fun testNoOpenTerminalMeansNoTabsNoTargetAndSendFailsCleanly() {
    // Fixture Setup — a fresh light project has no terminal sessions.
    // Verification — this is exactly what the Step Done / Do This Step / Ask for a Hint buttons gate on.
    assertEquals(emptyList<String>(), terminal.tabNames())
    assertNull("no terminal → no target", terminal.targetName())
    assertFalse("send must fail (not crash) when there's nowhere to inject", terminal.send("hello"))
  }
}
