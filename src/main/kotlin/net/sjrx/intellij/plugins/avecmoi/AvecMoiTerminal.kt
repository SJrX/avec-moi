package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory

/**
 * Shared "push" channel: inject text into the selected terminal session (where `claude` runs) and
 * submit it. Used by every avec-moi action that talks to Claude (Step Done, Ask for a Hint, …).
 *
 * Resolves the reworked-terminal engine (default since 2025.2): match the tool window's selected
 * content to a TerminalToolWindowTab and drive its TerminalView.
 */
object AvecMoiTerminal {

  /** Sends [text] to the selected terminal and submits it. Returns false if no terminal is available. */
  fun send(project: Project, text: String): Boolean {
    val toolWindow = ToolWindowManager.getInstance(project)
      .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID) ?: return false
    val selected = toolWindow.contentManager.selectedContent
    val tabs = TerminalToolWindowTabsManager.getInstance(project).tabs
    val view = (tabs.firstOrNull { it.content == selected } ?: tabs.firstOrNull())?.view ?: return false
    view.createSendTextBuilder().shouldExecute().send(text)
    return true
  }
}
