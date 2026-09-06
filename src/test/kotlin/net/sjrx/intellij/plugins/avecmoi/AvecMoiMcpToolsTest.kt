package net.sjrx.intellij.plugins.avecmoi

/**
 * The MCP tools end-to-end: an agent's `tools/call` in, the real project service mutated, the result
 * text out. This is the full path an agent actually drives (resolve the project by cwd → act on its
 * active session), so it catches wiring and argument-parsing regressions — especially the 1-based
 * (agent-facing) ↔ 0-based (internal) handoff numbering — that the service-level tests can't.
 */
class AvecMoiMcpToolsTest : AbstractAvecMoiTest() {

  private fun register(vararg handoffsJson: String): Unit {
    // language=JSON
    val result = callTool("register_handoffs", """
      { "projectPath": "$projectPath", "handoffs": [ ${handoffsJson.joinToString(", ")} ] }
    """.trimIndent())
    assertFalse("register_handoffs errored: ${result.contentText()}", result.isError())
  }

  // --- routing --------------------------------------------------------------------------------

  fun testResolveProjectMatchesTheOpenFixtureProject() {
    // language=JSON
    val result = callTool("resolve_project", """{ "projectPath": "$projectPath" }""")
    assertTrue("routing should match the fixture project, was: ${result.contentText()}",
      result.contentText().contains("Matched project"))
  }

  fun testListProjectsIncludesTheOpenFixtureProject() {
    // Exercise SUT — list_projects takes no projectPath
    val text = callTool("list_projects", "{}").contentText()
    // Verification
    assertTrue("expected the fixture project in: $text", text.contains(project.name))
  }

  fun testAProjectRoutedToolCallRecordsContactForTheConnectionIndicator() {
    // Fixture Setup — setUp cleared both runtime contact flags
    assertFalse(ring.isContacted())
    assertFalse(mcp.anyContact())

    // Exercise SUT — any project-routed tool call
    callTool("list_handoffs", """{ "projectPath": "$projectPath" }""")

    // Verification — this is what the connection dot reads: green (this project) and >= yellow (any)
    assertTrue("this project must be marked contacted (green indicator)", ring.isContacted())
    assertTrue("any-contact must be set (at least yellow indicator)", mcp.anyContact())
  }

  fun testGetGuidanceReturnsTheProjectsComposedBrief() {
    // Fixture Setup — set some guidance on the resolved project
    settings.goal = "Ship the parser"
    settings.reviewLevel = ReviewLevel.LIGHT

    // Exercise SUT
    val text = callTool("get_avecmoi_guidance", """{ "projectPath": "$projectPath" }""").contentText()

    // Verification — the brief reflects the settings
    assertTrue("goal in brief: $text", text.contains("Ship the parser"))
    assertTrue("light review in brief: $text", text.contains("keep it light"))
  }

  fun testAToolCallForAnUnknownDirectoryReportsNoProject() {
    // Exercise SUT
    // language=JSON
    val result = callTool("list_handoffs", """{ "projectPath": "/nowhere/at/all" }""")
    // Verification — graceful error, not a crash
    assertTrue(result.isError())
    assertTrue(result.contentText().contains("No open project"))
  }

  // --- register / list / add ------------------------------------------------------------------

  fun testRegisterThenListRoundTripsThroughTheProject() {
    // Exercise SUT
    register(
      """{ "file": "A.kt", "line": 3, "note": "implement A" }""",
      """{ "file": "B.kt", "note": "implement B" }""",
    )

    // Verification — the service holds them, and list_handoffs renders them back
    assertEquals(2, ring.all().size)
    val text = callTool("list_handoffs", """{ "projectPath": "$projectPath" }""").contentText()
    assertTrue(text.contains("implement A"))
    assertTrue(text.contains("A.kt:3"))
    assertTrue(text.contains("implement B"))
    assertTrue(text.contains("B.kt:1")) // line defaults to 1 when omitted
  }

  fun testRegisterSilentlyDropsHandoffEntriesMissingAFile() {
    // Exercise SUT — one good entry, one with no `file`
    // language=JSON
    val result = callTool("register_handoffs", """
      { "projectPath": "$projectPath",
        "handoffs": [ { "file": "Good.kt", "note": "keep" },
                      { "note": "no file, should be dropped" } ] }
    """.trimIndent())

    // Verification — the call succeeds, but only the well-formed entry lands (documents the contract:
    // per-entry `file` is required, and a malformed entry is skipped rather than failing the batch)
    assertFalse(result.isError())
    assertEquals(listOf("keep"), ring.all().map { it.note })
  }

  fun testListHandoffsOnAnEmptyProjectSaysSo() {
    val text = callTool("list_handoffs", """{ "projectPath": "$projectPath" }""").contentText()
    assertTrue(text.contains("No handoffs"))
  }

  fun testRegisterReplacesAnyPreviousHandoffs() {
    // Fixture Setup
    register("""{ "file": "Old.kt", "note": "old" }""")
    // Exercise SUT
    register("""{ "file": "New.kt", "note": "new" }""")
    // Verification
    assertEquals(1, ring.all().size)
    assertEquals("new", ring.all()[0].note)
  }

  fun testAddHandoffAppendsAndReportsItsOneBasedNumber() {
    // Fixture Setup
    register("""{ "file": "A.kt", "note": "first" }""")
    // Exercise SUT
    // language=JSON
    val result = callTool("add_handoff",
      """{ "projectPath": "$projectPath", "file": "B.kt", "note": "second" }""")
    // Verification
    assertTrue(result.contentText().contains("#2"))
    assertEquals(listOf("first", "second"), ring.all().map { it.note })
  }

  fun testAddHandoffAtAPositionInsertsThereOneBased() {
    // Fixture Setup
    register("""{ "file": "A.kt", "note": "a" }""", """{ "file": "B.kt", "note": "b" }""")
    // Exercise SUT — insert at the front (1-based position 1)
    // language=JSON
    callTool("add_handoff",
      """{ "projectPath": "$projectPath", "file": "Z.kt", "note": "z", "position": 1 }""")
    // Verification
    assertEquals(listOf("z", "a", "b"), ring.all().map { it.note })
  }

  fun testRegisterWithoutAHandoffsArrayIsAToolError() {
    // language=JSON
    val result = callTool("register_handoffs", """{ "projectPath": "$projectPath" }""")
    assertTrue(result.isError())
  }

  fun testAddHandoffWithoutANoteIsAToolError() {
    // language=JSON
    val result = callTool("add_handoff", """{ "projectPath": "$projectPath", "file": "A.kt" }""")
    assertTrue(result.isError())
  }

  // --- mark done / remove (1-based numbering) -------------------------------------------------

  fun testMarkHandoffDoneUsesOneBasedNumbersAndTouchesOnlyThatRow() {
    // Fixture Setup
    register("""{ "file": "A.kt", "note": "first" }""", """{ "file": "B.kt", "note": "second" }""")
    // Exercise SUT — mark the SECOND handoff (agent-facing #2 → internal index 1)
    // language=JSON
    val result = callTool("mark_handoff_done", """{ "projectPath": "$projectPath", "handoff": 2 }""")
    // Verification
    assertFalse(result.isError())
    assertFalse("row #1 must stay todo", ring.all()[0].done)
    assertTrue("row #2 must be done", ring.all()[1].done)
    assertTrue(callTool("list_handoffs", """{ "projectPath": "$projectPath" }""").contentText()
      .contains("[done]"))
  }

  fun testMarkHandoffDoneOutOfRangeIsAToolError() {
    // Fixture Setup
    register("""{ "file": "A.kt", "note": "only" }""")
    // Exercise SUT — there is no handoff #5
    // language=JSON
    val result = callTool("mark_handoff_done", """{ "projectPath": "$projectPath", "handoff": 5 }""")
    // Verification
    assertTrue(result.isError())
    assertFalse(ring.all()[0].done)
  }

  fun testRemoveHandoffOutOfRangeIsAToolError() {
    register("""{ "file": "A.kt", "note": "only" }""")
    // language=JSON
    val result = callTool("remove_handoff", """{ "projectPath": "$projectPath", "handoff": 9 }""")
    assertTrue(result.isError())
    assertEquals(1, ring.all().size)
  }

  fun testClearHandoffsEmptiesTheList() {
    // Fixture Setup
    register("""{ "file": "A.kt", "note": "a" }""", """{ "file": "B.kt", "note": "b" }""")
    // Exercise SUT
    callTool("clear_handoffs", """{ "projectPath": "$projectPath" }""")
    // Verification
    assertTrue(ring.isEmpty())
  }

  // --- navigation (error path is deterministic without an on-disk file) -----------------------

  fun testNavigateToAMissingFileIsAToolError() {
    // language=JSON
    val result = callTool("navigate_to",
      """{ "projectPath": "$projectPath", "file": "does/not/exist.kt", "line": 1 }""")
    assertTrue(result.isError())
    assertTrue(result.contentText().contains("not found"))
  }

  // --- sessions ---------------------------------------------------------------------------------

  fun testCreateSessionShowsUpAsActiveInListSessions() {
    // Exercise SUT
    callTool("create_session", """{ "projectPath": "$projectPath", "name": "Feature X" }""")
    // Verification
    val text = callTool("list_sessions", """{ "projectPath": "$projectPath" }""").contentText()
    assertTrue(text.contains("[ACTIVE]"))
    assertTrue(text.contains("Feature X"))
  }

  fun testSwitchSessionRoutesHandoffToolsToTheChosenSession() {
    // Fixture Setup — two sessions, each with its own handoff
    callTool("create_session", """{ "projectPath": "$projectPath", "name": "A" }""")
    val sessionA = ring.activeId()!!
    register("""{ "file": "A.kt", "note": "a-work" }""")
    callTool("create_session", """{ "projectPath": "$projectPath", "name": "B" }""")
    register("""{ "file": "B.kt", "note": "b-work" }""")

    // Exercise SUT — switch back to A
    val result = callTool("switch_session", """{ "projectPath": "$projectPath", "session": "$sessionA" }""")

    // Verification — list_handoffs now reflects A's handoff, not B's
    assertFalse(result.isError())
    val text = callTool("list_handoffs", """{ "projectPath": "$projectPath" }""").contentText()
    assertTrue(text.contains("a-work"))
    assertFalse(text.contains("b-work"))
  }

  fun testSwitchToAnUnknownSessionIsAToolError() {
    // language=JSON
    val result = callTool("switch_session", """{ "projectPath": "$projectPath", "session": "nope" }""")
    assertTrue(result.isError())
  }

  fun testRenameSessionIsReflectedInListSessions() {
    // Fixture Setup
    callTool("create_session", """{ "projectPath": "$projectPath", "name": "Old Name" }""")
    val id = ring.activeId()!!
    // Exercise SUT
    callTool("rename_session", """{ "projectPath": "$projectPath", "session": "$id", "name": "New Name" }""")
    // Verification
    val text = callTool("list_sessions", """{ "projectPath": "$projectPath" }""").contentText()
    assertTrue(text.contains("New Name"))
    assertFalse(text.contains("Old Name"))
  }

  fun testCompleteSessionMovesItToHistorical() {
    // Fixture Setup
    callTool("create_session", """{ "projectPath": "$projectPath", "name": "Wrap up" }""")
    val id = ring.activeId()!!
    // Exercise SUT
    val result = callTool("complete_session", """{ "projectPath": "$projectPath", "session": "$id" }""")
    // Verification
    assertFalse(result.isError())
    assertTrue(callTool("list_sessions", """{ "projectPath": "$projectPath" }""").contentText()
      .contains("[historical]"))
  }
}
