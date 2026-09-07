package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.components.service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers

/**
 * The HTTP transport itself — a real socket, real requests. The other MCP tests call the dispatch
 * seam directly; this proves the HttpServer wiring on top of it: /mcp actually serves JSON-RPC, and
 * a non-/mcp path returns a clean JSON 404 (the catch-all that stops an MCP client's OAuth
 * "re-authenticate" probe from choking on an HTML error page).
 */
class AvecMoiMcpHttpServerTest : AbstractAvecMoiTest() {

  private val client: HttpClient = HttpClient.newHttpClient()

  private fun port(): Int = mcp.startOnEphemeralPortForTest(testRootDisposable)

  private fun authHeader(): String = "Bearer " + service<AvecMoiMcpSettings>().token()

  fun testServesInitializeOverRealHttp() {
    // Exercise SUT — an authorized request (the header the connect command sets)
    val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${port()}/mcp"))
      .header("Content-Type", "application/json")
      .header("Authorization", authHeader())
      // language=JSON
      .POST(HttpRequest.BodyPublishers.ofString("""{ "jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {} }"""))
      .build()
    val response = client.send(request, BodyHandlers.ofString())

    // Verification
    assertEquals(200, response.statusCode())
    assertTrue("expected an initialize result: ${response.body()}", response.body().contains("serverInfo"))
    assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""))
  }

  fun testAMcpRequestWithoutTheAuthTokenIsRejectedWith401() {
    // Exercise SUT — a local process that doesn't know the secret (no Authorization header)
    val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${port()}/mcp"))
      .header("Content-Type", "application/json")
      // language=JSON
      .POST(HttpRequest.BodyPublishers.ofString("""{ "jsonrpc": "2.0", "id": 1, "method": "tools/list" }"""))
      .build()
    val response = client.send(request, BodyHandlers.ofString())

    // Verification — blocked before dispatch, with a clean JSON body
    assertEquals(401, response.statusCode())
    assertTrue("expected an unauthorized body: ${response.body()}", response.body().contains("unauthorized"))
  }

  fun testAMcpRequestWithTheWrongAuthTokenIsRejectedWith401() {
    // Exercise SUT — knows there's a bearer scheme but has the wrong secret
    val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${port()}/mcp"))
      .header("Content-Type", "application/json")
      .header("Authorization", "Bearer not-the-real-token")
      // language=JSON
      .POST(HttpRequest.BodyPublishers.ofString("""{ "jsonrpc": "2.0", "id": 1, "method": "tools/list" }"""))
      .build()
    val response = client.send(request, BodyHandlers.ofString())

    // Verification
    assertEquals(401, response.statusCode())
  }

  fun testABrowserOriginIsRejectedWith403() {
    // Exercise SUT — a webpage trying to drive the server (CSRF)
    val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${port()}/mcp"))
      .header("Origin", "http://evil.example.com")
      .header("Content-Type", "application/json")
      // language=JSON
      .POST(HttpRequest.BodyPublishers.ofString("""{ "jsonrpc": "2.0", "id": 1, "method": "tools/list" }"""))
      .build()
    val response = client.send(request, BodyHandlers.ofString())

    // Verification — blocked before dispatch
    assertEquals(403, response.statusCode())
  }

  fun testANonMcpPathReturnsACleanJson404() {
    // Exercise SUT — the kind of path an OAuth SDK probes
    val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${port()}/.well-known/oauth-authorization-server"))
      .GET()
      .build()
    val response = client.send(request, BodyHandlers.ofString())

    // Verification — 404, but JSON the SDK can parse, not the JDK's default HTML page
    assertEquals(404, response.statusCode())
    assertTrue("expected JSON body: ${response.body()}", response.body().contains("not_found"))
  }
}
