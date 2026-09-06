package net.sjrx.intellij.plugins.avecmoi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loopback-request guard that defends the MCP server against browser CSRF / DNS rebinding.
 * Pure logic: a real client (no Origin, loopback Host) is allowed; a site Origin or a non-local Host
 * is rejected.
 */
class McpOriginValidationTest {

  private fun ok(origin: String?, host: String?) = AvecMoiMcpService.isLocalOriginAndHost(origin, host)

  @Test
  fun aRealClientWithNoOriginAndLoopbackHostIsAllowed() {
    assertTrue(ok(null, "localhost:8282"))
    assertTrue(ok(null, "127.0.0.1:8282"))
    assertTrue(ok(null, null)) // minimal client, no headers
  }

  @Test
  fun aLoopbackOriginIsAllowed() {
    assertTrue(ok("http://localhost:8282", "localhost:8282"))
    assertTrue(ok("http://127.0.0.1:8282", "127.0.0.1:8282"))
    assertTrue(ok("http://[::1]:8282", "[::1]:8282"))
  }

  @Test
  fun aWebsiteOriginIsRejected() {
    assertFalse("browser CSRF", ok("http://evil.example.com", "localhost:8282"))
    assertFalse("https site", ok("https://app.example.com", "127.0.0.1:8282"))
  }

  @Test
  fun aNonLoopbackHostIsRejected() {
    assertFalse("DNS rebinding", ok(null, "evil.example.com"))
    assertFalse(ok(null, "evil.example.com:8282"))
  }
}
