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

  fun testNextSkipsCompletedHandoffs() {
    // Fixture Setup — the middle handoff is done
    ring.set(listOf(spec(note = "a"), spec(note = "b"), spec(note = "c")))
    ring.markDone(1, true)

    // Verification — Next jumps over "b", both forward and across the wrap
    assertEquals("a", ring.next()?.note)
    assertEquals("c", ring.next()?.note)
    assertEquals("a", ring.next()?.note)
  }

  fun testPrevSkipsCompletedHandoffs() {
    // Fixture Setup
    ring.set(listOf(spec(note = "a"), spec(note = "b"), spec(note = "c")))
    ring.markDone(1, true)

    // Verification
    assertEquals("c", ring.prev()?.note) // from the start, prev wraps to the last unfinished
    assertEquals("a", ring.prev()?.note) // skips "b"
  }

  fun testNavigationStillMovesWhenEveryHandoffIsDone() {
    // Fixture Setup — nothing left to do
    ring.set(listOf(spec(note = "a"), spec(note = "b")))
    ring.markDone(0, true)
    ring.markDone(1, true)

    // Verification — Next doesn't stick or return null; it falls back to a plain step
    assertEquals("a", ring.next()?.note)
    assertEquals("b", ring.next()?.note)
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

  fun testRemovingAHandoffBeforeTheCursorKeepsTheCursorOnTheSameItem() {
    // Fixture Setup — cursor parked on "c"
    ring.set(listOf(spec(note = "a"), spec(note = "b"), spec(note = "c"), spec(note = "d")))
    ring.next(); ring.next(); ring.next()
    assertEquals("c", ring.all()[ring.cursorIndex()].note)

    // Exercise SUT — remove a handoff BEFORE the cursor
    ring.remove(0)

    // Verification — "current" must still be "c", not silently jump to "d"
    assertEquals("c", ring.all()[ring.cursorIndex()].note)
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

  fun testEachSessionKeepsItsOwnNavigationCursor() {
    // Fixture Setup — advance the cursor in session A
    val a = ring.createSession("A", null)
    ring.set(listOf(spec(note = "a1"), spec(note = "a2")))
    ring.next() // A cursor -> 0
    ring.next() // A cursor -> 1
    assertEquals(1, ring.cursorIndex())

    // Exercise SUT — a second session starts navigation from scratch
    ring.createSession("B", null)
    ring.set(listOf(spec(note = "b1")))
    assertEquals("B starts with a fresh cursor", -1, ring.cursorIndex())

    // Verification — switching back to A restores A's cursor, independent of B
    ring.switchSession(a)
    assertEquals(1, ring.cursorIndex())
  }

  fun testHandoffsRegisteredWhileNoSessionExistsLandInAnAutoCreatedActiveSession() {
    // Exercise SUT — no createSession() first
    ring.add(spec(note = "solo"), null)

    // Verification — a session was created and it holds the handoff
    assertEquals(1, ring.listSessions().size)
    assertNotNull(ring.activeId())
    assertEquals("solo", ring.all().single().note)
  }

  fun testDeleteSessionRemovesItEntirely() {
    // Fixture Setup — a completed (historical) session, the case the UI offers delete for
    val keep = ring.createSession("Keep", null)
    val gone = ring.createSession("Prune me", null)
    ring.completeSession(gone)

    // Exercise SUT
    assertTrue(ring.deleteSession(gone))

    // Verification — it's gone, the other session is untouched
    assertEquals(listOf(keep), ring.listSessions().map { it.id })
  }

  fun testDeleteUnknownSessionReturnsFalse() {
    ring.createSession("Only", null)
    assertFalse(ring.deleteSession("nope"))
    assertEquals(1, ring.listSessions().size)
  }

  fun testDeletingTheActiveSessionFallsBackToAnotherOpenSession() {
    // Defensive: the UI only deletes historical sessions, but the service must keep the active
    // invariant if an active one is ever deleted.
    val first = ring.createSession("First", null)
    val second = ring.createSession("Second", null) // active
    assertEquals(second, ring.activeId())

    ring.deleteSession(second)

    assertEquals(first, ring.activeId())
    assertEquals(listOf(first), ring.listSessions().map { it.id })
  }
}
