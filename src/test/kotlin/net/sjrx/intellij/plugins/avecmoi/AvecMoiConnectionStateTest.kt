package net.sjrx.intellij.plugins.avecmoi

import net.sjrx.intellij.plugins.avecmoi.AvecMoiConnectionState.Companion.of
import net.sjrx.intellij.plugins.avecmoi.AvecMoiConnectionState.ELSEWHERE
import net.sjrx.intellij.plugins.avecmoi.AvecMoiConnectionState.HERE
import net.sjrx.intellij.plugins.avecmoi.AvecMoiConnectionState.NONE
import net.sjrx.intellij.plugins.avecmoi.AvecMoiConnectionState.SERVER_DOWN
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The connection-indicator decision. Pure logic (no platform): server-down wins over everything (no
 * point talking about connection if there's no server), then this-project (green) beats other-project
 * (yellow) beats nothing (red).
 */
class AvecMoiConnectionStateTest {

  @Test
  fun aServerThatIsNotRunningIsServerDown() {
    // Wins regardless of the contact flags — there's nothing to connect to.
    assertEquals(SERVER_DOWN, of(serverRunning = false, contactedThisProject = false, anyContact = false))
    assertEquals(SERVER_DOWN, of(serverRunning = false, contactedThisProject = true, anyContact = true))
  }

  @Test
  fun contactWithThisProjectIsHere() {
    assertEquals(HERE, of(serverRunning = true, contactedThisProject = true, anyContact = true))
  }

  @Test
  fun contactWithOnlyAnotherProjectIsElsewhere() {
    assertEquals(ELSEWHERE, of(serverRunning = true, contactedThisProject = false, anyContact = true))
  }

  @Test
  fun noContactAnywhereIsNone() {
    assertEquals(NONE, of(serverRunning = true, contactedThisProject = false, anyContact = false))
  }

  @Test
  fun thisProjectTakesPrecedenceEvenIfTheGlobalFlagLags() {
    // Defensive: HERE must win regardless of the any-contact flag's value (when the server is up).
    assertEquals(HERE, of(serverRunning = true, contactedThisProject = true, anyContact = false))
  }
}
