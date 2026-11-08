package net.sjrx.intellij.plugins.avecmoi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    // Assert the batch was accepted, NOT that it's warning-free: these fixtures use paths that don't
    // resolve on disk, so registration now (correctly) appends a "file not found" warning and flips
    // isError. The mechanics under test are the stored handoffs, verified by each caller.
    assertTrue("register_handoffs didn't register: ${result.contentText()}",
      result.contentText().contains("Registered"))
  }

  // --- routing --------------------------------------------------------------------------------

  fun testResolveProjectMatchesTheOpenFixtureProject() {
    // language=JSON
    val result = callTool("resolve_project", """{ "projectPath": "$projectPath" }""")
    assertTrue("routing should match the fixture project, was: ${result.contentText()}",
      result.contentText().contains("\"matched\":true"))
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

  fun testGuidanceMergesGlobalAndProjectFamiliarityWithProjectOverriding() {
    // Fixture Setup — a global default and a project-specific override for the same subject
    globalSettings.familiarity = mutableListOf(
      FamiliarityEntry().also { it.subject = "Kotlin"; it.level = Familiarity.FLUENT },
      FamiliarityEntry().also { it.subject = "SQL"; it.level = Familiarity.COMFORTABLE },
    )
    settings.familiarity = mutableListOf(
      FamiliarityEntry().also { it.subject = "Kotlin"; it.level = Familiarity.RUSTY },
    )

    // Exercise SUT — the brief the agent actually fetches
    val text = callTool("get_avecmoi_guidance", """{ "projectPath": "$projectPath" }""").contentText()

    // Verification — global-only row survives, project override replaces the global one
    assertTrue("global-only row must appear: $text", text.contains("SQL: comfortable"))
    assertTrue("project override must win: $text", text.contains("Kotlin: rusty"))
    assertFalse("the global Kotlin level must be overridden away: $text", text.contains("Kotlin: fluent"))
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

    // Verification — the service holds them, and list_handoffs renders them back (as JSON)
    assertEquals(2, ring.all().size)
    val text = callTool("list_handoffs", """{ "projectPath": "$projectPath" }""").contentText()
    assertTrue(text, text.contains("\"note\":\"implement A\""))
    assertTrue(text, text.contains("\"file\":\"A.kt\"") && text.contains("\"line\":3"))
    assertTrue(text, text.contains("\"note\":\"implement B\""))
    assertTrue(text, text.contains("\"file\":\"B.kt\"") && text.contains("\"line\":1")) // line defaults to 1
  }

  fun testRegisterSilentlyDropsHandoffEntriesMissingAFile() {
    // Exercise SUT — one good entry, one with no `file`
    // language=JSON
    val result = callTool("register_handoffs", """
      { "projectPath": "$projectPath",
        "handoffs": [ { "file": "Good.kt", "note": "keep" },
                      { "note": "no file, should be dropped" } ] }
    """.trimIndent())

    // Verification — only the well-formed entry lands (documents the contract: per-entry `file` is
    // required, and a malformed entry is skipped rather than failing the whole batch).
    assertTrue(result.contentText().contains("Registered 1 handoff"))
    assertEquals(listOf("keep"), ring.all().map { it.note })
  }

  // --- unresolved-file feedback (the monorepo-wrong-path failure) ------------------------------

  fun testRegisterWarnsWhenHandoffFilesDoNotExistButStillStoresThem() {
    // Exercise SUT — paths that don't resolve under the project root (the monorepo mistake: relative
    // to a subdirectory instead of the root)
    // language=JSON
    val result = callTool("register_handoffs", """
      { "projectPath": "$projectPath",
        "handoffs": [ { "file": "schema/validate.go", "note": "validate" },
                      { "file": "cmd/socle-gen/main.go", "note": "main" } ] }
    """.trimIndent())

    // Verification — the agent is told (isError so it notices), the offending paths + the project
    // root are named so it can self-correct, and the handoffs ARE stored (so the human sees the same
    // broken links and nothing is silently lost).
    assertTrue("agent must be told something's wrong", result.isError())
    val text = result.contentText()
    assertTrue("names the failure: $text", text.contains("don't exist under the project root"))
    assertTrue("names an offending path: $text", text.contains("schema/validate.go"))
    assertTrue("names the project root so the agent can fix: $text", text.contains(projectPath))
    assertEquals("handoffs still stored despite the warning", 2, ring.all().size)
  }

  fun testAddHandoffWarnsWhenItsFileDoesNotExist() {
    // language=JSON
    val result = callTool("add_handoff",
      """{ "projectPath": "$projectPath", "file": "nope/missing.kt", "note": "x" }""")
    assertTrue(result.isError())
    assertTrue(result.contentText().contains("don't exist under the project root"))
    assertEquals("still appended", 1, ring.all().size)
  }

  fun testUpdateHandoffWarnsWhenPointedAtAMissingFile() {
    // Fixture Setup
    register("""{ "file": "A.kt", "note": "x" }""")
    // Exercise SUT — repoint #1 at a bogus path
    // language=JSON
    val result = callTool("update_handoff",
      """{ "projectPath": "$projectPath", "handoff": 1, "file": "still/missing.kt" }""")
    // Verification — warned, but the edit applied
    assertTrue(result.isError())
    assertTrue(result.contentText().contains("don't exist under the project root"))
    assertEquals("still/missing.kt", ring.all()[0].file)
  }

  fun testListHandoffsFlagsAnUnresolvableFile() {
    // Fixture Setup — a path that won't resolve on disk (like the monorepo mistake)
    register("""{ "file": "ghost/missing.kt", "note": "implement me" }""")
    // Exercise SUT
    val text = callTool("list_handoffs", """{ "projectPath": "$projectPath" }""").contentText()
    // Verification — the agent can see from the listing that the file is missing (fileExists:false)
    assertTrue("list_handoffs should flag the missing file: $text", text.contains("\"fileExists\":false"))
  }

  fun testListHandoffsOnAnEmptyProjectSaysSo() {
    val text = callTool("list_handoffs", """{ "projectPath": "$projectPath" }""").contentText()
    assertTrue("empty project should list no handoffs: $text", text.contains("\"handoffs\":[]"))
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

  // --- kinds: to-do / read (work) vs reference (separate namespace) --------------------------

  fun testRegisterPartitionsWorkHandoffsFromReferencesAndReportsBoth() {
    // language=JSON
    val result = callTool("register_handoffs", """
      { "projectPath": "$projectPath", "handoffs": [
        { "file": "A.kt", "note": "implement" },
        { "file": "B.kt", "note": "read FooRepo before implementing", "kind": "read" },
        { "file": "C.kt", "note": "the pattern to mirror", "kind": "reference" } ] }
    """.trimIndent())
    assertTrue(result.contentText(), result.contentText().contains("2 handoff(s) and 1 reference(s)"))
    assertEquals("to-do + read are work items", 2, ring.all().size)
    assertEquals("reference is its own namespace", 1, ring.references().size)
  }

  fun testListHandoffsReturnsWorkAndReferenceNamespacesSeparately() {
    // language=JSON
    callTool("register_handoffs", """
      { "projectPath": "$projectPath", "handoffs": [
        { "file": "A.kt", "note": "implement", "kind": "todo" },
        { "file": "C.kt", "note": "context", "kind": "reference" } ] }
    """.trimIndent())
    val text = callTool("list_handoffs", """{ "projectPath": "$projectPath" }""").contentText()
    assertTrue(text, text.contains("\"kind\":\"todo\""))
    assertTrue("references get their own array + 1-based number: $text", text.contains("\"reference\":1"))
    assertTrue(text, text.contains("\"note\":\"context\""))
  }

  fun testAddReferenceReportsItAsAReferenceInItsOwnNamespace() {
    val result = callTool("add_handoff",
      """{ "projectPath": "$projectPath", "file": "C.kt", "note": "ctx", "kind": "reference" }""")
    assertTrue(result.contentText(), result.contentText().contains("Added reference #1"))
    assertEquals(0, ring.all().size)
    assertEquals(1, ring.references().size)
  }

  fun testRemoveHandoffWithReferenceFlagTargetsTheReferenceNamespace() {
    // language=JSON
    callTool("register_handoffs", """
      { "projectPath": "$projectPath", "handoffs": [
        { "file": "A.kt", "note": "implement" },
        { "file": "C.kt", "note": "ctx", "kind": "reference" } ] }
    """.trimIndent())
    // Exercise SUT — remove reference #1 (NOT work handoff #1)
    val result = callTool("remove_handoff", """{ "projectPath": "$projectPath", "handoff": 1, "reference": true }""")
    assertFalse(result.isError())
    assertEquals("the work handoff is untouched", 1, ring.all().size)
    assertTrue(ring.references().isEmpty())
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
      .contains("\"done\":true"))
  }

  fun testUpdateHandoffEditsInPlacePreservingDoneAndPosition() {
    // Fixture Setup
    register("""{ "file": "A.kt", "note": "first" }""", """{ "file": "B.kt", "note": "second" }""")
    callTool("mark_handoff_done", """{ "projectPath": "$projectPath", "handoff": 1 }""")

    // Exercise SUT — reword #1
    // language=JSON
    val result = callTool("update_handoff", """{ "projectPath": "$projectPath", "handoff": 1, "note": "reworded" }""")

    // Verification
    assertFalse(result.isError())
    assertEquals("reworded", ring.all()[0].note)
    assertTrue("done preserved", ring.all()[0].done)
    assertEquals("position preserved", "second", ring.all()[1].note)
  }

  fun testUpdateHandoffWithNoFieldsIsAToolError() {
    register("""{ "file": "A.kt", "note": "x" }""")
    val result = callTool("update_handoff", """{ "projectPath": "$projectPath", "handoff": 1 }""")
    assertTrue(result.isError())
  }

  fun testMarkHandoffDoneCanUnmarkWithDoneFalse() {
    // Fixture Setup
    register("""{ "file": "A.kt", "note": "x" }""")
    callTool("mark_handoff_done", """{ "projectPath": "$projectPath", "handoff": 1 }""")
    assertTrue(ring.all()[0].done)

    // Exercise SUT — un-mark
    // language=JSON
    callTool("mark_handoff_done", """{ "projectPath": "$projectPath", "handoff": 1, "done": false }""")

    // Verification
    assertFalse(ring.all()[0].done)
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
    assertTrue(text, text.contains("\"state\":\"active\""))
    assertTrue(text, text.contains("\"name\":\"Feature X\""))
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
      .contains("\"state\":\"historical\""))
  }

  // --- phrasebooks ----------------------------------------------------------------------------

  /** The phrasebook of each work handoff as list_handoffs reports it: (code, gloss) pairs, empty if none. */
  private fun listedPhrases(): List<List<Pair<String, String>>> {
    val text = callTool("list_handoffs", """{ "projectPath": "$projectPath" }""").contentText()
    return Json.parseToJsonElement(text).jsonObject.getValue("handoffs").jsonArray.map { h ->
      h.jsonObject["phrases"]?.jsonArray?.map {
        it.jsonObject.getValue("code").jsonPrimitive.content to it.jsonObject.getValue("gloss").jsonPrimitive.content
      } ?: emptyList()
    }
  }

  fun testRegisterStoresEachHandoffsPhrasebookAndListReturnsIt() {
    // Exercise SUT
    register(
      // language=JSON
      """
        { "file": "A.kt", "note": "implement A", "phrases": [
          { "code": "val m = marker ?: return null", "gloss": "elvis early-return" },
          { "code": "xs.filter { it.done }" }
        ] }
      """.trimIndent(),
      """{ "file": "B.kt", "note": "implement B" }""",
    )

    // Verification — the first handoff has its phrasebook (a missing gloss is empty); the second none
    assertEquals(
      listOf(
        listOf("val m = marker ?: return null" to "elvis early-return", "xs.filter { it.done }" to ""),
        emptyList(),
      ),
      listedPhrases(),
    )
  }

  fun testPhrasesWithoutCodeAreDropped() {
    // Exercise SUT
    // language=JSON
    register("""{ "file": "A.kt", "note": "x", "phrases": [ { "gloss": "no code" }, { "code": "  " }, { "code": "a ?: b" } ] }""")

    // Verification
    assertEquals(listOf(Phrase("a ?: b")), ring.all().single().phrases)
  }

  fun testAddHandoffTakesAPhrasebook() {
    // Exercise SUT
    // language=JSON
    callTool("add_handoff", """
      { "projectPath": "$projectPath", "file": "A.kt", "note": "x",
        "phrases": [ { "code": "when (k) { A -> 1; B -> 2 }", "gloss": "exhaustive when" } ] }
    """.trimIndent())

    // Verification
    assertEquals(listOf(Phrase("when (k) { A -> 1; B -> 2 }", "exhaustive when")), ring.all().single().phrases)
  }

  fun testUpdateHandoffReplacesThePhrasebookAndKeepsTheNote() {
    // Fixture Setup
    // language=JSON
    register("""{ "file": "A.kt", "note": "keep me", "phrases": [ { "code": "old" } ] }""")

    // Exercise SUT — phrases only
    // language=JSON
    val result = callTool("update_handoff", """
      { "projectPath": "$projectPath", "handoff": 1, "phrases": [ { "code": "new", "gloss": "fresh" } ] }
    """.trimIndent())

    // Verification
    assertFalse(result.contentText(), result.isError())
    assertEquals(listOf(Phrase("new", "fresh")), ring.all().single().phrases)
    assertEquals("keep me", ring.all().single().note)
  }

  fun testUpdateHandoffWithAnEmptyPhrasesArrayClearsThePhrasebook() {
    // Fixture Setup
    register("""{ "file": "A.kt", "note": "x", "phrases": [ { "code": "old" } ] }""")

    // Exercise SUT
    callTool("update_handoff", """{ "projectPath": "$projectPath", "handoff": 1, "phrases": [] }""")

    // Verification
    assertEquals(emptyList<Phrase>(), ring.all().single().phrases)
  }

  fun testUpdateHandoffWithoutPhrasesLeavesThePhrasebookAlone() {
    // Fixture Setup
    register("""{ "file": "A.kt", "note": "x", "phrases": [ { "code": "keep" } ] }""")

    // Exercise SUT — a note-only edit, and a re-point (which rebuilds the handoff)
    callTool("update_handoff", """{ "projectPath": "$projectPath", "handoff": 1, "note": "reworded" }""")
    callTool("update_handoff", """{ "projectPath": "$projectPath", "handoff": 1, "line": 5 }""")

    // Verification
    assertEquals(listOf(Phrase("keep")), ring.all().single().phrases)
  }

  // --- set_familiarity ------------------------------------------------------------------------

  private fun setFamiliarity(arguments: String) = callTool("set_familiarity", arguments)

  fun testSetFamiliarityWritesEachRowToTheStoreForItsScope() {
    // Exercise SUT
    // language=JSON
    val result = setFamiliarity("""
      { "projectPath": "$projectPath", "entries": [
        { "subject": "Kotlin", "level": "rusty", "interest": "keen", "scope": "global" },
        { "subject": "Inlay rendering", "level": "new", "scope": "project" }
      ] }
    """.trimIndent())

    // Verification — global rows go IDE-wide, project rows stay here
    assertFalse(result.contentText(), result.isError())
    assertEquals(listOf("Kotlin"), globalSettings.familiarity.map { it.subject })
    assertEquals(Familiarity.RUSTY, globalSettings.familiarity.single().level)
    assertEquals(Interest.KEEN, globalSettings.familiarity.single().interest)
    assertEquals(listOf("Inlay rendering"), settings.familiarity.map { it.subject })
  }

  fun testWhatSetFamiliarityWritesShowsUpInTheGuidance() {
    // Fixture Setup
    // language=JSON
    setFamiliarity("""{ "projectPath": "$projectPath", "entries": [ { "subject": "Kotlin", "level": "rusty", "scope": "global" } ] }""")

    // Exercise SUT
    val brief = callTool("get_avecmoi_guidance", """{ "projectPath": "$projectPath" }""").contentText()

    // Verification
    assertTrue(brief, brief.contains("Kotlin: rusty (neutral) [all projects]"))
  }

  fun testSetFamiliarityUpdatesAnExistingRowInPlace() {
    // Fixture Setup
    settings.familiarity = mutableListOf(
      FamiliarityEntry().also { it.subject = "Parser"; it.level = Familiarity.NEW; it.interest = Interest.KEEN },
    )

    // Exercise SUT — correct the level only
    // language=JSON
    setFamiliarity("""{ "projectPath": "$projectPath", "entries": [ { "subject": "parser", "level": "comfortable", "scope": "project" } ] }""")

    // Verification
    val row = settings.familiarity.single()
    assertEquals("Parser", row.subject)
    assertEquals(Familiarity.COMFORTABLE, row.level)
    assertEquals(Interest.KEEN, row.interest)
  }

  fun testSetFamiliarityRemovesRows() {
    // Fixture Setup
    globalSettings.familiarity = mutableListOf(FamiliarityEntry().also { it.subject = "SQL"; it.level = Familiarity.FLUENT })

    // Exercise SUT
    // language=JSON
    val result = setFamiliarity("""{ "projectPath": "$projectPath", "remove": [ { "subject": "SQL", "scope": "global" } ] }""")

    // Verification
    assertFalse(result.contentText(), result.isError())
    assertEquals(emptyList<FamiliarityEntry>(), globalSettings.familiarity)
  }

  fun testANewSubjectWithoutALevelIsReportedAndNotWritten() {
    // Exercise SUT
    // language=JSON
    val result = setFamiliarity("""{ "projectPath": "$projectPath", "entries": [ { "subject": "Gradle", "scope": "project" } ] }""")

    // Verification
    assertTrue(result.isError())
    assertTrue(result.contentText(), result.contentText().contains("Gradle"))
    assertEquals(emptyList<FamiliarityEntry>(), settings.familiarity)
  }

  fun testAnEntryWithABadScopeIsReportedWhileTheGoodOnesAreStillWritten() {
    // Exercise SUT
    // language=JSON
    val result = setFamiliarity("""
      { "projectPath": "$projectPath", "entries": [
        { "subject": "Kotlin", "level": "rusty", "scope": "everywhere" },
        { "subject": "Parser", "level": "new", "scope": "project" }
      ] }
    """.trimIndent())

    // Verification
    assertTrue(result.isError())
    assertEquals(emptyList<FamiliarityEntry>(), globalSettings.familiarity)
    assertEquals(listOf("Parser"), settings.familiarity.map { it.subject })
  }

  fun testSetFamiliarityWithNothingToDoIsAToolError() {
    assertTrue(setFamiliarity("""{ "projectPath": "$projectPath" }""").isError())
  }

  // --- set_guidance ---------------------------------------------------------------------------

  private fun setGuidance(arguments: String) = callTool("set_guidance", arguments)

  fun testSetGuidanceSavesTheGoalAndItShowsUpInTheBrief() {
    // Exercise SUT
    // language=JSON
    val result = setGuidance("""{ "projectPath": "$projectPath", "goal": "Learn coroutines" }""")

    // Verification
    assertFalse(result.contentText(), result.isError())
    assertEquals("Learn coroutines", settings.goal)
    val brief = callTool("get_avecmoi_guidance", """{ "projectPath": "$projectPath" }""").contentText()
    assertTrue(brief, brief.contains("GOAL: Learn coroutines"))
  }

  fun testAppendingExtraContextKeepsWhatTheHumanWrote() {
    // Fixture Setup
    settings.extraContext = "Use JUnit 4."

    // Exercise SUT
    // language=JSON
    val result = setGuidance("""{ "projectPath": "$projectPath", "appendExtraContext": "Don't touch the generated parser." }""")

    // Verification — and the reply repeats back what was saved
    assertFalse(result.contentText(), result.isError())
    assertEquals("Use JUnit 4.\nDon't touch the generated parser.", settings.extraContext)
    assertTrue(result.contentText(), result.contentText().contains("Don't touch the generated parser."))
  }

  fun testReplacingExtraContextOverwritesIt() {
    // Fixture Setup
    settings.extraContext = "Use JUnit 4."

    // Exercise SUT
    // language=JSON
    setGuidance("""{ "projectPath": "$projectPath", "replaceExtraContext": "Use JUnit 5." }""")

    // Verification
    assertEquals("Use JUnit 5.", settings.extraContext)
  }

  fun testAnEmptyGoalClearsIt() {
    // Fixture Setup
    settings.goal = "Ship the parser"

    // Exercise SUT
    // language=JSON
    setGuidance("""{ "projectPath": "$projectPath", "goal": "" }""")

    // Verification
    assertEquals("", settings.goal)
  }

  fun testAppendAndReplaceTogetherIsAToolErrorAndSavesNothing() {
    // Fixture Setup
    settings.extraContext = "Use JUnit 4."

    // Exercise SUT
    // language=JSON
    val result = setGuidance("""
      { "projectPath": "$projectPath", "goal": "Ship it", "appendExtraContext": "a", "replaceExtraContext": "b" }
    """.trimIndent())

    // Verification
    assertTrue(result.isError())
    assertEquals("", settings.goal)
    assertEquals("Use JUnit 4.", settings.extraContext)
  }

  fun testSetGuidanceWithNothingToDoIsAToolError() {
    assertTrue(setGuidance("""{ "projectPath": "$projectPath" }""").isError())
  }
}
