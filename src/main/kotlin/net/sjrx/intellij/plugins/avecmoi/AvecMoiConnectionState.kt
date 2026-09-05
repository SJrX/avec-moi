package net.sjrx.intellij.plugins.avecmoi

/**
 * The three states of the agent-connection indicator, derived from the two runtime contact flags.
 * Precedence: contact with THIS project (green) beats contact with only another project (yellow),
 * which beats no contact at all (red). Pure, so the decision is unit-testable independent of the UI
 * that maps it to an icon / label / help message.
 */
enum class AvecMoiConnectionState {
  HERE, ELSEWHERE, NONE;

  companion object {
    fun of(contactedThisProject: Boolean, anyContact: Boolean): AvecMoiConnectionState = when {
      contactedThisProject -> HERE
      anyContact -> ELSEWHERE
      else -> NONE
    }
  }
}
