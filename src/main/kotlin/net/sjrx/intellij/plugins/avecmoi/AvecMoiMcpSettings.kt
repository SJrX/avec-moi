package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.security.SecureRandom

/**
 * Application-level MCP server settings (the server is one per IDE process, so these are IDE-wide,
 * not per project). Stored in the IDE config dir. Holds the shared-secret auth token the server
 * requires as an `Authorization: Bearer` header, so only a client that knows it (i.e. one the human
 * configured via the connect command) can drive the IDE.
 */
@Service(Service.Level.APP)
@State(name = "AvecMoiMcpSettings", storages = [Storage("avec-moi.xml")])
class AvecMoiMcpSettings : PersistentStateComponent<AvecMoiMcpSettings.State> {

  class State {
    var port: Int = AvecMoiMcpService.PORT
    var authToken: String = ""
  }

  private var state = State()

  override fun getState(): State = state

  override fun loadState(state: State) {
    this.state = state
  }

  var port: Int
    get() = state.port
    set(value) { state.port = value }

  /** The current auth token, minting one on first use so it's stable across restarts. */
  fun token(): String {
    if (state.authToken.isBlank()) state.authToken = generateToken()
    return state.authToken
  }

  /** Roll the token (e.g. if it leaked). Any connected client must re-run the connect command. */
  fun regenerateToken(): String {
    state.authToken = generateToken()
    return state.authToken
  }

  companion object {
    // Alphanumeric only: safe in an HTTP header value, a shell/console command, and a URL — no
    // quoting or escaping needed. 24 chars over a 62-char alphabet ≈ 143 bits: plenty for a
    // localhost shared secret, without being obnoxiously long.
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private const val TOKEN_LENGTH = 24

    internal fun generateToken(): String {
      val rnd = SecureRandom()
      // nextInt(bound) is unbiased, so no modulo skew across the alphabet.
      return buildString(TOKEN_LENGTH) { repeat(TOKEN_LENGTH) { append(ALPHABET[rnd.nextInt(ALPHABET.length)]) } }
    }
  }
}
