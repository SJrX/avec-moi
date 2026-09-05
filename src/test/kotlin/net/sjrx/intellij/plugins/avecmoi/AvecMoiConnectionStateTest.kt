package net.sjrx.intellij.plugins.avecmoi

import net.sjrx.intellij.plugins.avecmoi.AvecMoiConnectionState.ELSEWHERE
import net.sjrx.intellij.plugins.avecmoi.AvecMoiConnectionState.HERE
import net.sjrx.intellij.plugins.avecmoi.AvecMoiConnectionState.NONE
import net.sjrx.intellij.plugins.avecmoi.AvecMoiConnectionState.Companion.of
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The green/yellow/red decision behind the connection indicator. Pure logic (no platform), pinning the
 * precedence the UI was tuned around: this-project wins over other-project wins over nothing.
 */
class AvecMoiConnectionStateTest {

  @Test
  fun contactWithThisProjectIsHere() {
    assertEquals(HERE, of(contactedThisProject = true, anyContact = true))
  }

  @Test
  fun contactWithOnlyAnotherProjectIsElsewhere() {
    assertEquals(ELSEWHERE, of(contactedThisProject = false, anyContact = true))
  }

  @Test
  fun noContactAnywhereIsNone() {
    assertEquals(NONE, of(contactedThisProject = false, anyContact = false))
  }

  @Test
  fun thisProjectTakesPrecedenceEvenIfTheGlobalFlagLags() {
    // Defensive: HERE must win regardless of the any-contact flag's value.
    assertEquals(HERE, of(contactedThisProject = true, anyContact = false))
  }
}
