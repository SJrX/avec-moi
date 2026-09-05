package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Base for avec-moi tests. Runs on the real IntelliJ platform via [BasePlatformTestCase] — the
 * services under test are driven for real, nothing is mocked, so a green test means the real thing
 * works. Following the four-phase style (Fixture Setup / Exercise SUT / Verification), this base
 * holds the platform plumbing and JSON-RPC envelope so each test body shows only what matters.
 */
abstract class AbstractAvecMoiTest : BasePlatformTestCase() {

  override fun setUp() {
    super.setUp()
    // The light project (and the app-level MCP service) are reused across tests in a run, so their
    // state leaks between methods unless we wipe it. Reset to a clean slate here.
    ring.loadState(AvecMoiRingService.PersistedState())
    ring.resetContactForTest()
    mcp.resetContactForTest()
  }

  // --- ring / session DSL ------------------------------------------------------------------------

  /** The project-level session + handoff store under test. */
  protected val ring: AvecMoiRingService
    get() = project.service()

  /** Copy state out of [from] into a brand-new store, exactly as reopening the IDE would. */
  protected fun roundTrip(from: AvecMoiRingService): AvecMoiRingService =
    AvecMoiRingService(project).also { it.loadState(from.getState()) }

  /** A handoff spec with sensible defaults, so a test names only the field it's exercising. */
  protected fun spec(file: String = "Sample.kt", line: Int = 1, note: String = "implement me") =
    AvecMoiRingService.HandoffSpec(file, line, note)

  // --- MCP protocol DSL --------------------------------------------------------------------------

  /** The app-level MCP server under test. */
  protected val mcp: AvecMoiMcpService
    get() = service()

  /** Send a raw request body straight to the dispatch seam; return the raw (status, body). */
  internal fun post(body: String): AvecMoiMcpService.McpHttpResult = mcp.handleMcpPost(body)

  /**
   * Send a well-formed JSON-RPC request and return the parsed `result` object. Fails the test if the
   * server answered with a non-200 status or a JSON-RPC `error` — so the call site only handles the
   * happy path. The envelope isn't what any test is about, so it's assembled here; [params] is the raw
   * JSON for the `params` field — pass it as an injected multi-line literal at the call site.
   */
  protected fun call(method: String, params: String = "{}"): JsonObject {
    // language=JSON
    val request = """{ "jsonrpc": "2.0", "id": 1, "method": "$method", "params": $params }"""
    val res = post(request)
    assertEquals("HTTP status for $method", 200, res.status)
    val obj = Json.parseToJsonElement(res.body!!).jsonObject
    assertNull("Unexpected JSON-RPC error for $method: ${obj["error"]}", obj["error"])
    return obj["result"]!!.jsonObject
  }

  /** The path an agent would pass as `projectPath` to reach the fixture project (its cwd). */
  protected val projectPath: String
    get() = project.basePath ?: error("test fixture project has no basePath")

  /** Invoke an MCP tool by name and return its result object (the one carrying `content` / `isError`). */
  protected fun callTool(name: String, arguments: String): JsonObject {
    // language=JSON
    val params = """{ "name": "$name", "arguments": $arguments }"""
    return call("tools/call", params)
  }

  /** The text of an MCP tool result's first content block. */
  protected fun JsonObject.contentText(): String =
    getValue("content").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content

  /** Whether an MCP tool result is flagged as an error. */
  protected fun JsonObject.isError(): Boolean = getValue("isError").jsonPrimitive.boolean
}
