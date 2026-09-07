package net.sjrx.intellij.plugins.avecmoi

/**
 * The states of the agent-connection indicator. If the MCP server isn't even running (e.g. the port
 * is taken by another IDE) that wins — there's nothing to connect to. Otherwise: contact with THIS
 * project (green) beats an agent that's connected but hasn't acted here — idle, for this project and
 * maybe every project (yellow) — which beats nothing having connected at all (red). Pure, so the
 * decision is unit-testable independent of the UI that maps it to an icon / label / help.
 */
enum class AvecMoiConnectionState(val label: String) {
  SERVER_DOWN("not running"),
  HERE("connected"),
  IDLE("idle"),
  NONE("waiting for agent");

  companion object {
    fun of(serverRunning: Boolean, contactedThisProject: Boolean, anyContact: Boolean): AvecMoiConnectionState =
      when {
        !serverRunning -> SERVER_DOWN
        contactedThisProject -> HERE
        anyContact -> IDLE
        else -> NONE
      }
  }
}
