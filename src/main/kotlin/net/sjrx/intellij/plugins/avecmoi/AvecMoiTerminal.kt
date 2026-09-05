package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Thin facade over [AvecMoiTerminalService] for actions that push a line to the agent's terminal. */
object AvecMoiTerminal {
  fun send(project: Project, text: String): Boolean = project.service<AvecMoiTerminalService>().send(text)
}
