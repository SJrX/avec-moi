package net.sjrx.intellij.plugins.avecmoi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared-secret auth: token generation and Bearer validation. Pure (no platform). Pins the
 * properties the human asked for — HTTP/console/URL-safe, secure-random, not obnoxiously long — and
 * that only an exact `Bearer <token>` is accepted.
 */
class McpAuthTest {

  @Test
  fun generatedTokensAreAlphanumericAndAReasonableLength() {
    // Alphanumeric only = safe in an HTTP header, a shell command, and a URL with no escaping.
    repeat(50) {
      val t = AvecMoiMcpSettings.generateToken()
      assertTrue("token must be 43 url/console-safe chars (~256 bits), was '$t'", t.matches(Regex("[A-Za-z0-9]{43}")))
    }
  }

  @Test
  fun generatedTokensDoNotCollide() {
    // SecureRandom over ~256 bits: 100 draws should be unique (a collision here means a broken source).
    val tokens = (1..100).map { AvecMoiMcpSettings.generateToken() }.toSet()
    assertTrue("secure-random tokens must not repeat, got ${tokens.size}/100 unique", tokens.size == 100)
  }

  @Test
  fun onlyAnExactBearerTokenIsAccepted() {
    val token = "Tok3nValueGoesHere012345"
    assertTrue(AvecMoiMcpService.isValidToken("Bearer $token", token))
    assertFalse("wrong secret", AvecMoiMcpService.isValidToken("Bearer wrong", token))
    assertFalse("missing scheme", AvecMoiMcpService.isValidToken(token, token))
    assertFalse("scheme is case-sensitive", AvecMoiMcpService.isValidToken("bearer $token", token))
    assertFalse("no header at all", AvecMoiMcpService.isValidToken(null, token))
    assertFalse("empty header", AvecMoiMcpService.isValidToken("", token))
    assertFalse("token is a prefix but not exact", AvecMoiMcpService.isValidToken("Bearer ${token}extra", token))
  }
}
