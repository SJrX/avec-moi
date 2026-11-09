package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The agent writes files behind the IDE's back and registers handoffs on them milliseconds later
 * (#45). The tools must see the disk as it is now, not the IDE's cached view of it. Each test writes
 * through java.io (bypassing the VFS), checks the cache really is stale, then calls the MCP tool.
 */
class AvecMoiExternalWriteTest : AbstractAvecMoiTest() {

  private lateinit var dir: File

  override fun setUp() {
    super.setUp()
    dir = FileUtil.createTempDirectory("avecmoi-external", null)
    // Let the VFS know the directory and its (empty) contents, so a file added later is genuinely unknown.
    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!.children
  }

  private fun knownToIde(file: File) = LocalFileSystem.getInstance().findFileByPath(file.path) != null

  /** Write [text] the way the agent does: straight to disk, with a fresh timestamp. */
  private fun writeBehindTheIdesBack(file: File, text: String) {
    file.writeText(text)
    file.setLastModified(file.lastModified() + 2000)
  }

  private fun register(file: File, line: Int) = callTool("register_handoffs", """
    { "projectPath": "$projectPath", "handoffs": [ { "file": "${file.path}", "line": $line, "note": "implement" } ] }
  """.trimIndent())

  fun testAFileWrittenJustBeforeRegisteringIsFoundNotReportedMissing() {
    // Fixture Setup
    val file = File(dir, "Partition.kt")
    writeBehindTheIdesBack(file, "fun a() {}\nfun partition() {}\n")
    assertFalse("precondition: the IDE hasn't seen the new file yet", knownToIde(file))

    // Exercise SUT
    val result = register(file, line = 2)

    // Verification — no missing-file warning, and the handoff is anchored
    assertFalse(result.contentText(), result.isError())
    assertTrue("anchored", ring.all().single().marker?.isValid == true)
    assertEquals(2, ring.all().single().line)
  }

  fun testAFileChangedJustBeforeRegisteringIsAnchoredAgainstItsNewText() {
    // Fixture Setup — the IDE has the old, 2-line version loaded
    val file = File(dir, "Partition.kt")
    writeBehindTheIdesBack(file, "fun a() {}\nfun b() {}\n")
    val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!
    val doc = FileDocumentManager.getInstance().getDocument(vf)!!
    assertEquals(2, doc.lineCount - 1)

    // ...then the agent rewrites it with partition() on line 4
    writeBehindTheIdesBack(file, "fun a() {}\nfun b() {}\nfun c() {}\nfun partition() {}\n")

    // Exercise SUT
    register(file, line = 4)

    // Verification — anchored on line 4 of the new text, not clamped to the stale 2-line copy
    assertEquals(4, ring.all().single().line)
    assertTrue(doc.text, doc.text.contains("partition"))
  }

  fun testListHandoffsReportsAFileThatAppearedAfterRegistering() {
    // Fixture Setup — registered before the file existed (so it was reported missing)
    val file = File(dir, "Later.kt")
    register(file, line = 1)
    writeBehindTheIdesBack(file, "fun later() {}\n")
    assertFalse("precondition: the IDE hasn't seen the new file yet", knownToIde(file))

    // Exercise SUT
    val text = callTool("list_handoffs", """{ "projectPath": "$projectPath" }""").contentText()

    // Verification
    val row = Json.parseToJsonElement(text).jsonObject.getValue("handoffs").jsonArray.single().jsonObject
    assertTrue(text, row.getValue("fileExists").jsonPrimitive.boolean)
  }

  fun testNavigateToFindsAFileWrittenJustBefore() {
    // Fixture Setup
    val file = File(dir, "Fresh.kt")
    writeBehindTheIdesBack(file, "fun fresh() {}\n")
    assertFalse("precondition: the IDE hasn't seen the new file yet", knownToIde(file))

    // Exercise SUT
    val result = callTool("navigate_to", """{ "projectPath": "$projectPath", "file": "${file.path}", "line": 1 }""")

    // Verification
    assertFalse(result.contentText(), result.isError())

    // Cleanup — navigation opened an editor (on the next EDT cycle); release it before teardown
    PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    FileEditorManager.getInstance(project).let { fem -> fem.openFiles.forEach(fem::closeFile) }
  }

  // --- edits outside the handoff tools (#65) ---------------------------------------------------------

  /** Load [file] into the IDE as a document, as if it were open in an editor. */
  private fun loadedDocument(file: File) =
    FileDocumentManager.getInstance().getDocument(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!)!!

  private fun refreshFiles(vararg files: String) = callTool(
    "refresh_files_in_ide",
    """{ "projectPath": "$projectPath", "files": [ ${files.joinToString { "\"$it\"" }} ] }""",
  )

  private fun rows(result: kotlinx.serialization.json.JsonObject) =
    Json.parseToJsonElement(result.contentText()).jsonObject.getValue("files").jsonArray.map { it.jsonObject }

  fun testMarkingAHandoffDoneShowsTheCodeTheAgentJustWroteThere() {
    // Fixture Setup — a handoff on a loaded file, which the agent then implements behind the IDE's back
    val file = File(dir, "Partition.kt")
    writeBehindTheIdesBack(file, "fun partition() = TODO()\n")
    val doc = loadedDocument(file)
    register(file, line = 1)
    writeBehindTheIdesBack(file, "fun partition() = listOf(1, 2)\n")

    // Exercise SUT
    val result = callTool("mark_handoff_done", """{ "projectPath": "$projectPath", "handoff": 1 }""")

    // Verification
    assertFalse(result.contentText(), result.isError())
    assertTrue(doc.text, doc.text.contains("listOf(1, 2)"))
  }

  fun testRefreshFilesReloadsAChangedFileAndFindsANewOne() {
    // Fixture Setup
    val changed = File(dir, "Changed.kt")
    writeBehindTheIdesBack(changed, "fun old() {}\n")
    val doc = loadedDocument(changed)
    writeBehindTheIdesBack(changed, "fun new() {}\n")
    val created = File(dir, "Created.kt")
    writeBehindTheIdesBack(created, "fun created() {}\n")
    assertFalse("precondition: the IDE hasn't seen the new file yet", knownToIde(created))

    // Exercise SUT
    val result = refreshFiles(changed.path, created.path)

    // Verification
    assertFalse(result.contentText(), result.isError())
    assertEquals("fun new() {}\n", doc.text)
    assertTrue("the new file is known now", knownToIde(created))
    assertEquals(
      listOf(true, true),
      rows(result).map { it.getValue("onDisk").jsonPrimitive.boolean },
    )
  }

  fun testRefreshFilesReportsAFileThatIsNotOnDisk() {
    // Exercise SUT
    val result = refreshFiles(File(dir, "Missing.kt").path)

    // Verification
    val row = rows(result).single()
    assertFalse(row.toString(), row.getValue("onDisk").jsonPrimitive.boolean)
    assertFalse(row.toString(), row.getValue("unsavedInIde").jsonPrimitive.boolean)
  }

  fun testRefreshFilesNeedsAtLeastOneFile() {
    // Exercise SUT
    val result = callTool("refresh_files_in_ide", """{ "projectPath": "$projectPath", "files": [] }""")

    // Verification
    assertTrue(result.contentText(), result.isError())
  }

  fun testRefreshFilesLeavesTheHumansUnsavedEditsAloneAndSaysSo() {
    // Fixture Setup — the human has typed into the file without saving
    val file = File(dir, "Mine.kt")
    writeBehindTheIdesBack(file, "fun mine() {}\n")
    val doc = loadedDocument(file)
    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { doc.insertString(0, "// typing\n") }

    // Exercise SUT
    val result = refreshFiles(file.path)

    // Verification
    assertTrue(rows(result).single().getValue("unsavedInIde").jsonPrimitive.boolean)
    assertTrue(doc.text, doc.text.startsWith("// typing"))
  }
}
