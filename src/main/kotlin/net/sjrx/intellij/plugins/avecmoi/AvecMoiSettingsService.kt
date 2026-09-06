package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

/**
 * Per-project avec-moi settings, persisted at the workspace level (per-user, not in VCS). Starts with
 * one local-behavior toggle; the agent-guidance knobs (goal, familiarity, review level, …) will land
 * here too as the settings page grows.
 */
@Service(Service.Level.PROJECT)
@State(name = "AvecMoiSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class AvecMoiSettingsService : PersistentStateComponent<AvecMoiSettingsService.State> {

  class State {
    /** Reveal the terminal (and select the target tab) after sending an action to the agent. */
    var focusTerminalOnSend: Boolean = true
  }

  private var state = State()

  override fun getState(): State = state

  override fun loadState(state: State) {
    this.state = state
  }

  var focusTerminalOnSend: Boolean
    get() = state.focusTerminalOnSend
    set(value) {
      state.focusTerminalOnSend = value
    }
}
