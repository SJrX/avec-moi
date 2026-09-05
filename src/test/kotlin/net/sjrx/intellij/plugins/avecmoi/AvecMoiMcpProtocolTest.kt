package net.sjrx.intellij.plugins.avecmoi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The JSON-RPC wire behaviour of the MCP server, exercised through the real dispatch seam. No mocks:
 * the request goes in as literal JSON and the real handler answers. This is where the "reconnect
 * didn't flip the indicator" bug lived, so that scenario is pinned first.
 */
class AvecMoiMcpProtocolTest : AbstractAvecMoiTest() {

  fun testInitializePostAloneMarksAnAgentAsConnected() {
    // Fixture Setup — the reconnect scenario: a handshake with no project tool call
    assertFalse("precondition: setUp reset the flag", mcp.anyContact())

    // Exercise SUT
    // language=JSON
    call("initialize", """{ "protocolVersion": "2025-06-18" }""")

    // Verification
    assertTrue("initialize alone must flip the connection indicator", mcp.anyContact())
  }

  fun testInitializeReportsServerIdentityAndInstructions() {
    // Exercise SUT
    // language=JSON
    val result = call("initialize", """{ "protocolVersion": "2025-06-18" }""")

    // Verification
    assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
    assertEquals("avec-moi", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    assertNotNull("connect-time guidance must travel with the server", result["instructions"])
  }

  fun testToolsListAdvertisesEveryDeclaredToolWithAnInputSchema() {
    // Exercise SUT
    val result = call("tools/list")

    // Verification
    val tools = result["tools"]!!.jsonArray
    val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
    assertEquals(AvecMoiMcpService.TOOL_NAMES.toSet(), names) // the advertised set == the declared set
    tools.forEach { t ->
      assertNotNull("tool ${t.jsonObject["name"]} is missing an inputSchema", t.jsonObject["inputSchema"])
    }
  }

  fun testEveryToolSchemaIsAnObjectAndProjectScopedToolsRequireProjectPath() {
    // Exercise SUT
    val tools = call("tools/list")["tools"]!!.jsonArray

    // Verification — the schema is the agent's contract; a project-scoped tool that forgets to require
    // projectPath would route nowhere. (ping / list_projects are intentionally not project-scoped.)
    val projectScoped = AvecMoiMcpService.TOOL_NAMES.toSet() - setOf("ping", "list_projects")
    tools.forEach { t ->
      val name = t.jsonObject["name"]!!.jsonPrimitive.content
      val schema = t.jsonObject["inputSchema"]!!.jsonObject
      assertEquals("tool $name inputSchema.type", "object", schema["type"]!!.jsonPrimitive.content)
      assertNotNull("tool $name has no properties block", schema["properties"])
      if (name in projectScoped) {
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("project-scoped tool $name must require projectPath", "projectPath" in required)
      }
    }
  }

  fun testPingToolReturnsPong() {
    // Exercise SUT
    // language=JSON
    val result = call("tools/call", """{ "name": "ping", "arguments": {} }""")

    // Verification
    val text = result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
    assertTrue("ping should pong, was: $text", text.contains("pong"))
    assertFalse(result["isError"]!!.jsonPrimitive.boolean)
  }

  fun testTheResponseEchoesANumericRequestId() {
    // Exercise SUT — a client matches responses to requests by id, so it must come back unchanged
    // language=JSON
    val res = post("""{ "jsonrpc": "2.0", "id": 42, "method": "tools/list" }""")

    // Verification
    assertEquals(200, res.status)
    assertEquals(42, Json.parseToJsonElement(res.body!!).jsonObject["id"]!!.jsonPrimitive.int)
  }

  fun testTheResponseEchoesAStringRequestId() {
    // Exercise SUT — ids may be strings; the type must be preserved, not coerced
    // language=JSON
    val res = post("""{ "jsonrpc": "2.0", "id": "abc-1", "method": "tools/list" }""")

    // Verification
    assertEquals("abc-1", Json.parseToJsonElement(res.body!!).jsonObject["id"]!!.jsonPrimitive.content)
  }

  fun testInitializeDefaultsTheProtocolVersionWhenTheClientOmitsIt() {
    // Exercise SUT — no params at all
    val result = call("initialize")

    // Verification — falls back to the server's supported version rather than echoing null
    assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
  }

  fun testToolsCallWithoutAToolNameIsAToolError() {
    // Exercise SUT
    // language=JSON
    val result = call("tools/call", """{ "arguments": {} }""")

    // Verification
    assertTrue(result.isError())
  }

  fun testUnknownMethodYieldsJsonRpcMethodNotFound() {
    // Exercise SUT
    // language=JSON
    val res = post("""{ "jsonrpc": "2.0", "id": 9, "method": "no/such" }""")

    // Verification
    assertEquals(200, res.status)
    val error = Json.parseToJsonElement(res.body!!).jsonObject["error"]!!.jsonObject
    assertEquals(-32601, error["code"]!!.jsonPrimitive.int)
  }

  fun testUnknownToolReportsAToolLevelError() {
    // Exercise SUT — a valid tools/call for a tool that doesn't exist
    // language=JSON
    val result = call("tools/call", """{ "name": "does_not_exist", "arguments": {} }""")

    // Verification — JSON-RPC succeeds, but the MCP result is flagged isError
    assertTrue(result["isError"]!!.jsonPrimitive.boolean)
  }

  fun testMalformedJsonBodyIsRejectedWith400() {
    // Exercise SUT — deliberately not JSON
    val res = post("this is not json")

    // Verification
    assertEquals(400, res.status)
  }

  fun testNotificationWithoutIdIsAcceptedWithNoBody() {
    // Exercise SUT — a JSON-RPC notification carries no id and expects no response
    // language=JSON
    val res = post("""{ "jsonrpc": "2.0", "method": "notifications/initialized" }""")

    // Verification
    assertEquals(202, res.status)
    assertNull(res.body)
  }
}
