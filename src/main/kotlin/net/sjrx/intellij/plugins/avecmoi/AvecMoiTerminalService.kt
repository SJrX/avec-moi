package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory

/**
 * Per-project state + logic for the "push" channel: which terminal tab avec-moi injects into, and
 * the injection itself. The human can pin a target via the tool-window dropdown; otherwise we fall
 * back to the selected terminal tab (then the first).
 */
@Service(Service.Level.PROJECT)
class AvecMoiTerminalService(private val project: Project) {

  @Volatile
  private var preferredName: String? = null

  fun tabs(): List<TerminalToolWindowTab> = TerminalToolWindowTabsManager.getInstance(project).tabs

  fun setPreferred(name: String?) {
    preferredName = name
  }

  fun preferredName(): String? = preferredName

  /** The pinned tab if it still exists, else the selected terminal tab, else the first. */
  fun target(): TerminalToolWindowTab? {
    val tabs = tabs()
    if (tabs.isEmpty()) return null
    preferredName?.let { name -> tabs.firstOrNull { it.content.displayName == name }?.let { return it } }
    val selected = ToolWindowManager.getInstance(project)
      .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)?.contentManager?.selectedContent
    return tabs.firstOrNull { it.content == selected } ?: tabs.first()
  }

  fun targetName(): String? = target()?.content?.displayName

  /** Inject [text] into the target terminal and submit it. Returns false if there's no terminal. */
  fun send(text: String): Boolean {
    val tab = target() ?: return false
    val view = tab.view ?: return false
    view.createSendTextBuilder().shouldExecute().send(text)
    when (project.service<AvecMoiSettingsService>().terminalOnSend) {
      TerminalRevealBehavior.SHOW -> revealTerminal(tab, focus = false)
      TerminalRevealBehavior.SHOW_AND_FOCUS -> revealTerminal(tab, focus = true)
      TerminalRevealBehavior.NONE -> Unit
    }
    return true
  }

  /** Show the terminal tool window and select [tab]. When [focus] is false, keyboard focus stays put. */
  private fun revealTerminal(tab: TerminalToolWindowTab, focus: Boolean) {
    val toolWindow = ToolWindowManager.getInstance(project)
      .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID) ?: return
    toolWindow.activate({ toolWindow.contentManager.setSelectedContent(tab.content, focus) }, focus)
  }
}
