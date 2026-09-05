package net.sjrx.intellij.plugins.avecmoi

/**
 * The session + handoff state machine, driven through the real project service. These are the parts
 * that break silently (routing to the wrong session, progress getting clobbered, state lost on
 * restart), so they're what's worth pinning.
 */
class AvecMoiRingServiceTest : AbstractAvecMoiTest() {

  fun testRegisteringWithNoSessionAutoCreatesAnActiveOne() {
    // Fixture Setup — no session created yet

    // Exercise SUT
    ring.set(listOf(spec(note = "partition")))

    // Verification
    assertSize(1, ring.listSessions())
    assertSize(1, ring.all())
    assertNotNull(ring.activeId())
  }

  fun testHandoffsInOneSessionDoNotLeakIntoAnother() {
    // Fixture Setup
    val a = ring.createSession("Feature A", "main")
    ring.set(listOf(spec(note = "A work")))

    // Exercise SUT
    ring.createSession("Feature B", "main") // creating a session makes it active

    // Verification
    assertSize(0, ring.all())               // B starts empty
    ring.switchSession(a)
    assertSize(1, ring.all())               // A still holds its handoff
    assertEquals("A work", ring.all()[0].note)
  }

  fun testMarkDoneFlagsOnlyTheTargetedHandoff() {
    // Fixture Setup
    ring.set(listOf(spec(note = "first"), spec(note = "second")))

    // Exercise SUT
    assertTrue(ring.markDone(0, true))

    // Verification
    assertTrue(ring.all()[0].done)
    assertFalse(ring.all()[1].done)
  }

  fun testAddReturnsOneBasedPositionAndPreservesExistingProgress() {
    // Fixture Setup
    ring.set(listOf(spec(note = "first")))
    ring.markDone(0, true)

    // Exercise SUT
    val position = ring.add(spec(note = "second"), null)

    // Verification
    assertEquals(2, position)               // 1-based, appended
    assertTrue(ring.all()[0].done)          // existing done-state untouched
    assertEquals("second", ring.all()[1].note)
  }

  fun testRegisterReplacesTheWholeSetAndResetsProgress() {
    // Fixture Setup
    ring.set(listOf(spec(note = "old")))
    ring.markDone(0, true)

    // Exercise SUT
    ring.set(listOf(spec(note = "new")))

    // Verification
    assertSize(1, ring.all())
    assertEquals("new", ring.all()[0].note)
    assertFalse(ring.all()[0].done)         // a fresh set is not done
  }

  fun testRemoveDropsTheTargetAndRenumbersTheRest() {
    // Fixture Setup
    ring.set(listOf(spec(note = "a"), spec(note = "b"), spec(note = "c")))

    // Exercise SUT
    assertTrue(ring.remove(1))              // drop "b"

    // Verification
    assertEquals(listOf("a", "c"), ring.all().map { it.note })
  }

  fun testNextCyclesThroughHandoffsAndWrapsAround() {
    // Fixture Setup
    ring.set(listOf(spec(note = "a"), spec(note = "b")))

    // Exercise SUT / Verification
    assertEquals("a", ring.next()?.note)
    assertEquals("b", ring.next()?.note)
    assertEquals("a", ring.next()?.note)    // wraps back to the start
  }

  fun testCompletingASessionMovesItToHistoricalAndClearsActive() {
    // Fixture Setup
    val a = ring.createSession("Done soon", null)
    ring.set(listOf(spec()))

    // Exercise SUT
    assertTrue(ring.completeSession(a))

    // Verification
    assertNull(ring.activeId())                       // nothing active once the only session is done
    assertTrue(ring.listSessions().single().historical)
    assertFalse(ring.switchSession(a))                // a historical session can't be reactivated
  }

  fun testStateSurvivesAGetStateLoadStateRoundTrip() {
    // Fixture Setup
    val a = ring.createSession("Feature A", "main")
    ring.set(listOf(spec(file = "Quick.kt", line = 7, note = "partition"), spec(note = "merge")))
    ring.markDone(0, true)

    // Exercise SUT — persist and reload into a fresh store, as an IDE restart does
    val reloaded = roundTrip(ring)

    // Verification
    val sessions = reloaded.listSessions()
    assertSize(1, sessions)
    assertEquals("Feature A", sessions[0].name)
    assertEquals("main", sessions[0].branch)
    assertEquals(a, reloaded.activeId())              // the active choice is remembered

    val handoffs = sessions[0].handoffs
    assertSize(2, handoffs)
    assertEquals("partition", handoffs[0].note)
    assertEquals(7, handoffs[0].line)                 // persisted line survives (marker is re-created lazily)
    assertTrue(handoffs[0].done)                      // and so does per-handoff progress
    assertFalse(handoffs[1].done)
  }
}
