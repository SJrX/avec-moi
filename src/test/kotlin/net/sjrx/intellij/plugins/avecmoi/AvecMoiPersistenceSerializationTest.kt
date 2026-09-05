package net.sjrx.intellij.plugins.avecmoi

import com.intellij.util.xmlb.XmlSerializer

/**
 * Persistence at the XML layer, not just in-process. The [AvecMoiRingServiceTest] round-trip exercises
 * getState/loadState but keeps the same objects; this serializes [AvecMoiRingService.PersistedState] to
 * an XML element and back exactly as the platform does when writing/reading workspace.xml, so it
 * actually depends on the @XCollection / @get annotations being right. This is the "survives an IDE
 * restart" contract the plugin was hand-verified against.
 */
class AvecMoiPersistenceSerializationTest : AbstractAvecMoiTest() {

  fun testPersistedStateSurvivesAnXmlSerializeDeserializeRoundTrip() {
    // Fixture Setup — build real state through the service
    val active = ring.createSession("Feature A", "main")
    ring.set(listOf(spec(file = "Quick.kt", line = 7, note = "partition"), spec(note = "merge")))
    ring.markDone(0, true)

    // Exercise SUT — serialize to XML and read it back, as save/load of workspace.xml does
    val element = XmlSerializer.serialize(ring.getState())
    val restored = XmlSerializer.deserialize(element, AvecMoiRingService.PersistedState::class.java)
    val reloaded = AvecMoiRingService(project).also { it.loadState(restored) }

    // Verification — nothing was dropped by (de)serialization
    val session = reloaded.listSessions().single()
    assertEquals("Feature A", session.name)
    assertEquals("main", session.branch)
    assertEquals(active, reloaded.activeId())

    assertEquals(listOf("partition", "merge"), session.handoffs.map { it.note })
    assertEquals(7, session.handoffs[0].line)
    assertTrue("done-state must persist", session.handoffs[0].done)
    assertFalse(session.handoffs[1].done)
  }

  fun testHistoricalSessionsAndTheIdCounterSurviveSerialization() {
    // Fixture Setup — an open session and a completed (historical) one
    ring.createSession("Open one", null)
    val old = ring.createSession("Old one", null)
    ring.completeSession(old)

    // Exercise SUT
    val element = XmlSerializer.serialize(ring.getState())
    val restored = XmlSerializer.deserialize(element, AvecMoiRingService.PersistedState::class.java)
    val reloaded = AvecMoiRingService(project).also { it.loadState(restored) }

    // Verification — historical flag survives, and the seq counter keeps ids unique after reload
    assertEquals(2, reloaded.listSessions().size)
    assertTrue(reloaded.listSessions().any { it.historical })

    val idsBeforeNew = reloaded.listSessions().map { it.id }.toSet()
    val next = reloaded.createSession("After reload", null)
    assertFalse("a fresh id must not collide with a pre-serialization one", next in idsBeforeNew)
    assertEquals(3, reloaded.listSessions().map { it.id }.toSet().size)
  }
}
