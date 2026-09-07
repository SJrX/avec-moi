package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.XCollection

/**
 * Application-level avec-moi settings that apply across every project. Currently just the GLOBAL
 * familiarity rows — facts about the developer ("fluent in Kotlin") that shouldn't be re-entered per
 * project. A project's own rows (in [AvecMoiSettingsService]) override these by subject. Stored in the
 * IDE config dir, alongside [AvecMoiMcpSettings].
 */
@Service(Service.Level.APP)
@State(name = "AvecMoiGlobalSettings", storages = [Storage("avec-moi.xml")])
class AvecMoiGlobalSettings : PersistentStateComponent<AvecMoiGlobalSettings.State> {

  class State {
    @get:XCollection(style = XCollection.Style.v2)
    var familiarity: MutableList<FamiliarityEntry> = mutableListOf()
  }

  private var state = State()

  override fun getState(): State = state

  override fun loadState(state: State) {
    this.state = state
  }

  var familiarity: MutableList<FamiliarityEntry>
    get() = state.familiarity
    set(value) { state.familiarity = value }
}
