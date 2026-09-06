package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

/**
 * Settings | Tools | Avec Moi. Currently just the local-behavior toggle; the agent-guidance knobs
 * (goal, familiarity, review level, …) will get their own group here.
 */
class AvecMoiConfigurable(project: Project) : BoundConfigurable("Avec Moi") {

  private val settings = project.service<AvecMoiSettingsService>()

  override fun createPanel(): DialogPanel = panel {
    group("Local Behavior") {
      row {
        checkBox("Reveal the terminal when sending an action to the agent")
          .bindSelected(settings::focusTerminalOnSend)
          .comment(
            "After Step Done / Do This Step / Ask for a Hint, show the terminal and select the " +
              "target tab so you can watch the agent — without stealing keyboard focus from the editor.",
          )
      }
    }
  }
}
