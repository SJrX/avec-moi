package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

/** What happens to the target terminal when a quick action is sent to the agent. */
enum class TerminalRevealBehavior(val label: String) {
  SHOW("Show terminal"),
  SHOW_AND_FOCUS("Show and focus terminal"),
  NONE("Do nothing"),
}

/**
 * Per-project avec-moi settings, persisted at the workspace level (per-user, not in VCS). The
 * agent-guidance knobs (goal, familiarity, review level, …) will land here too as the settings page
 * grows.
 */
@Service(Service.Level.PROJECT)
@State(name = "AvecMoiSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class AvecMoiSettingsService : PersistentStateComponent<AvecMoiSettingsService.State> {

  class State {
    /** What to do with the terminal after sending a quick action to the agent. */
    var terminalOnSend: TerminalRevealBehavior = TerminalRevealBehavior.SHOW
  }

  private var state = State()

  override fun getState(): State = state

  override fun loadState(state: State) {
    this.state = state
  }

  var terminalOnSend: TerminalRevealBehavior
    get() = state.terminalOnSend
    set(value) {
      state.terminalOnSend = value
    }
}
