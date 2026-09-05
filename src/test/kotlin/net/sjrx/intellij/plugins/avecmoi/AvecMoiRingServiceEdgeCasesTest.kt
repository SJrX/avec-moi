package net.sjrx.intellij.plugins.avecmoi

/**
 * Boundary behaviour of the ring/session store — the null returns, clamps, and state transitions
 * that are easy to get subtly wrong and hard to notice by hand.
 */
class AvecMoiRingServiceEdgeCasesTest : AbstractAvecMoiTest() {

  fun testNextAndPrevReturnNullWhenThereAreNoHandoffs() {
    // Fixture Setup — an active session exists but is empty
    ring.createSession("Empty", null)

    // Verification
    assertNull(ring.next())
    assertNull(ring.prev())
    assertEquals(-1, ring.cursorIndex())
  }

  fun testPrevWrapsFromTheStartToTheLastHandoff() {
    // Fixture Setup
    ring.set(listOf(spec(note = "a"), spec(note = "b"), spec(note = "c")))

    // Exercise SUT — first prev() from the initial cursor wraps to the end
    // Verification
    assertEquals("c", ring.prev()?.note)
    assertEquals("b", ring.prev()?.note)
  }

  fun testMarkDoneCanBeToggledBackToNotDone() {
    // Fixture Setup
    ring.set(listOf(spec(note = "a")))
    ring.markDone(0, true)

    // Exercise SUT
    assertTrue(ring.markDone(0, false))

    // Verification
    assertFalse(ring.all()[0].done)
  }

  fun testMarkDoneAndRemoveReturnFalseForAnOutOfRangeIndex() {
    // Fixture Setup
    ring.set(listOf(spec(note = "only")))

    // Verification — no throw, just a false so the caller can report a clean error
    assertFalse(ring.markDone(5, true))
    assertFalse(ring.remove(5))
    assertFalse(ring.remove(-1))
    assertEquals(1, ring.all().size)
  }

  fun testAddClampsAnOutOfRangePositionToTheEnds() {
    // Fixture Setup
    ring.set(listOf(spec(note = "a"), spec(note = "b")))

    // Exercise SUT — a wildly-too-large index appends rather than throwing
    ring.add(spec(note = "z"), 999)

    // Verification
    assertEquals(listOf("a", "b", "z"), ring.all().map { it.note })
  }

  fun testRemovingBeforeTheCursorKeepsItInBounds() {
    // Fixture Setup — walk the cursor to the last handoff
    ring.set(listOf(spec(note = "a"), spec(note = "b"), spec(note = "c")))
    ring.next(); ring.next(); ring.next() // cursor now at index 2 ("c")
    assertEquals(2, ring.cursorIndex())

    // Exercise SUT — drop the last handoff
    ring.remove(2)

    // Verification — the cursor can't dangle past the end
    assertTrue("cursor must stay within bounds", ring.cursorIndex() < ring.all().size)
  }

  fun testCompletingTheActiveSessionPromotesAnotherOpenSessionToActive() {
    // Fixture Setup — two open sessions; the second is active
    val first = ring.createSession("First", null)
    val second = ring.createSession("Second", null)
    assertEquals(second, ring.activeId())

    // Exercise SUT
    ring.completeSession(second)

    // Verification — the remaining open session takes over as active (not left with nothing)
    assertEquals(first, ring.activeId())
  }

  fun testSwitchingToAHistoricalSessionIsRejected() {
    // Fixture Setup
    val s = ring.createSession("Done", null)
    ring.completeSession(s)

    // Verification
    assertFalse(ring.switchSession(s))
    assertNull(ring.activeId())
  }

  fun testHandoffsRegisteredWhileNoSessionExistsLandInAnAutoCreatedActiveSession() {
    // Exercise SUT — no createSession() first
    ring.add(spec(note = "solo"), null)

    // Verification — a session was created and it holds the handoff
    assertEquals(1, ring.listSessions().size)
    assertNotNull(ring.activeId())
    assertEquals("solo", ring.all().single().note)
  }
}
