package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.panel

/**
 * Settings | Tools | Avec Moi. Currently the quick-action behavior; the agent-guidance knobs
 * (goal, familiarity, review level, …) will get their own group here.
 */
class AvecMoiConfigurable(project: Project) : BoundConfigurable("Avec Moi") {

  private val settings = project.service<AvecMoiSettingsService>()

  override fun createPanel(): DialogPanel = panel {
    group("Quick Actions") {
      buttonsGroup("When you send a quick action (Step Done, Do This Step, Ask for a Hint), the target terminal will:") {
        TerminalRevealBehavior.entries.forEach { behavior ->
          row { radioButton(behavior.label, behavior) }
        }
      }.bind(settings::terminalOnSend)
    }
  }
}
