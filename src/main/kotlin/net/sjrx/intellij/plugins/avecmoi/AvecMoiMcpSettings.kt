package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level MCP server settings (the server is one per IDE process, so these are IDE-wide,
 * not per project). Stored in the IDE config dir.
 */
@Service(Service.Level.APP)
@State(name = "AvecMoiMcpSettings", storages = [Storage("avec-moi.xml")])
class AvecMoiMcpSettings : PersistentStateComponent<AvecMoiMcpSettings.State> {

  class State {
    var port: Int = AvecMoiMcpService.PORT
  }

  private var state = State()

  override fun getState(): State = state

  override fun loadState(state: State) {
    this.state = state
  }

  var port: Int
    get() = state.port
    set(value) { state.port = value }
}
