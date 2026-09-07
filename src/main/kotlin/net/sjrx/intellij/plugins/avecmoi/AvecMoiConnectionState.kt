package net.sjrx.intellij.plugins.avecmoi

/**
 * The states of the agent-connection indicator. If the MCP server isn't even running (e.g. the port
 * is taken by another IDE) that wins — there's nothing to connect to. Otherwise: contact with THIS
 * project (green) beats contact with only another project (yellow), which beats no contact (red).
 * Pure, so the decision is unit-testable independent of the UI that maps it to an icon / label / help.
 */
enum class AvecMoiConnectionState {
  SERVER_DOWN, HERE, ELSEWHERE, NONE;

  companion object {
    fun of(serverRunning: Boolean, contactedThisProject: Boolean, anyContact: Boolean): AvecMoiConnectionState =
      when {
        !serverRunning -> SERVER_DOWN
        contactedThisProject -> HERE
        anyContact -> ELSEWHERE
        else -> NONE
      }
  }
}
