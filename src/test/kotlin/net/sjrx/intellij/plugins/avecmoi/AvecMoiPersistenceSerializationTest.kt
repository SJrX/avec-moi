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

  private fun familiarity(subject: String, level: Familiarity, interest: Interest, scope: FamiliarityScope) =
    FamiliarityEntry().also { it.subject = subject; it.level = level; it.interest = interest; it.scope = scope }

  fun testProjectSettingsSurviveAnXmlRoundTripIncludingEveryFamiliarityField() {
    // Fixture Setup — exercise every persisted field, especially the enums and the familiarity list
    val state = AvecMoiSettingsService.State().apply {
      goal = "Ship the parser"
      reviewLevel = ReviewLevel.THOROUGH
      extraContext = "Prefer immutable data."
      terminalOnSend = TerminalRevealBehavior.SHOW_AND_FOCUS
      editorNoteScope = EditorNoteScope.CURRENT
      noteWrapMaxColumns = 110
      familiarity = mutableListOf(
        familiarity("Kotlin", Familiarity.FLUENT, Interest.KEEN, FamiliarityScope.GLOBAL),
        familiarity("Regex", Familiarity.NEW, Interest.UNINTERESTED, FamiliarityScope.PROJECT),
      )
    }

    // Exercise SUT — serialize to XML and back, as save/load of the workspace file does
    val restored = XmlSerializer.deserialize(XmlSerializer.serialize(state), AvecMoiSettingsService.State::class.java)

    // Verification — scalars
    assertEquals("Ship the parser", restored.goal)
    assertEquals(ReviewLevel.THOROUGH, restored.reviewLevel)
    assertEquals("Prefer immutable data.", restored.extraContext)
    assertEquals(TerminalRevealBehavior.SHOW_AND_FOCUS, restored.terminalOnSend)
    assertEquals(EditorNoteScope.CURRENT, restored.editorNoteScope)
    assertEquals(110, restored.noteWrapMaxColumns)
    // Verification — familiarity rows, incl. the newer interest + scope fields
    assertEquals(2, restored.familiarity.size)
    restored.familiarity[0].let {
      assertEquals("Kotlin", it.subject)
      assertEquals(Familiarity.FLUENT, it.level)
      assertEquals(Interest.KEEN, it.interest)
      assertEquals(FamiliarityScope.GLOBAL, it.scope)
    }
    restored.familiarity[1].let {
      assertEquals(Interest.UNINTERESTED, it.interest)
      assertEquals(FamiliarityScope.PROJECT, it.scope)
    }
  }

  fun testGlobalSettingsFamiliaritySurvivesAnXmlRoundTrip() {
    val state = AvecMoiGlobalSettings.State().apply {
      familiarity = mutableListOf(familiarity("SQL", Familiarity.COMFORTABLE, Interest.NEUTRAL, FamiliarityScope.GLOBAL))
    }
    val restored = XmlSerializer.deserialize(XmlSerializer.serialize(state), AvecMoiGlobalSettings.State::class.java)
    assertEquals(1, restored.familiarity.size)
    assertEquals("SQL", restored.familiarity[0].subject)
    assertEquals(Familiarity.COMFORTABLE, restored.familiarity[0].level)
  }

  fun testMcpServerPortAndAuthTokenSurviveAnXmlRoundTrip() {
    val state = AvecMoiMcpSettings.State().apply {
      port = 9191
      authToken = "S0meSecretT0kenValue"
    }
    val restored = XmlSerializer.deserialize(XmlSerializer.serialize(state), AvecMoiMcpSettings.State::class.java)
    assertEquals(9191, restored.port)
    assertEquals("S0meSecretT0kenValue", restored.authToken)
  }

  fun testAFamiliarityRowSavedBeforeInterestAndScopeExistedLoadsWithDefaults() {
    // The upgrade path: a config written before those fields must still load, defaulting them.
    val element = XmlSerializer.serialize(
      familiarity("Kotlin", Familiarity.FLUENT, Interest.KEEN, FamiliarityScope.GLOBAL),
    )
    // Drop the fields that didn't exist in the older config.
    element.children.removeIf { it.getAttributeValue("name") in setOf("interest", "scope") }

    val restored = XmlSerializer.deserialize(element, FamiliarityEntry::class.java)
    assertEquals("Kotlin", restored.subject)
    assertEquals(Familiarity.FLUENT, restored.level)
    assertEquals("missing interest must default to NEUTRAL", Interest.NEUTRAL, restored.interest)
    assertEquals("missing scope must default to PROJECT", FamiliarityScope.PROJECT, restored.scope)
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
