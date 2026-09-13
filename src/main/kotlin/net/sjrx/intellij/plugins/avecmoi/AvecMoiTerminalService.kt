package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.content.Content
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.io.IOException
import javax.swing.SwingUtilities
import kotlin.coroutines.cancellation.CancellationException

/**
 * Per-project state + logic for the "push" channel: which terminal tab avec-moi injects into, and
 * the injection itself. The human can pin a target via the tool-window dropdown; otherwise we fall
 * back to the selected terminal tab (then the first).
 *
 * Works with BOTH terminal engines. The reworked (Gen2) terminal exposes tabs via
 * [TerminalToolWindowTabsManager]; the classic/legacy terminal keeps its sessions as widgets under
 * [TerminalToolWindowManager]. Both surface as [com.intellij.ui.content.Content] tabs in the same
 * Terminal tool window, so we enumerate both and hide the difference behind [Target].
 */
@Service(Service.Level.PROJECT)
class AvecMoiTerminalService(private val project: Project) {

  @Volatile
  private var preferredName: String? = null

  /** Dedup key for the "no terminal though tabs exist" INFO breadcrumb, so polling doesn't spam the log. */
  @Volatile
  private var lastEmptyDiag: String? = null

  /** One injectable terminal tab, regardless of which engine backs it. */
  private sealed interface Target {
    val name: String
    val content: Content

    /** Inject [text] and submit it. Returns false if the tab can't currently accept input. */
    fun send(text: String): Boolean
  }

  private inner class ReworkedTarget(private val tab: TerminalToolWindowTab) : Target {
    override val name: String get() = tab.content.displayName ?: "Terminal"
    override val content: Content get() = tab.content
    override fun send(text: String): Boolean {
      val view = tab.view ?: return false
      // Bracketed paste gives the TUI an explicit paste boundary, so the trailing Enter
      // (shouldExecute) submits cleanly instead of being swallowed when the agent's paste-detection
      // buffers a long line.
      view.createSendTextBuilder().useBracketedPasteMode().shouldExecute().send(text)
      return true
    }
  }

  private inner class LegacyTarget(override val content: Content, private val widget: TerminalWidget) : Target {
    override val name: String get() = content.displayName ?: "Terminal"
    override fun send(text: String): Boolean {
      // Match the reworked path's shape: wrap in a bracketed-paste boundary then Enter, written
      // straight to the PTY, so a long/multi-line message lands as one paste and the CR submits.
      widget.ttyConnector?.let { tty ->
        try {
          tty.write("\u001B[200~$text\u001B[201~\r")
          return true
        } catch (_: IOException) {
          // fall through to the high-level path below
        }
      }
      // Not connected yet (no TtyConnector) — the accessor-backed call waits for the shell.
      return try {
        widget.sendCommandToExecute(text)
        true
      } catch (_: Exception) {
        false
      }
    }
  }

  /**
   * Every terminal tab across both engines, reworked first, de-duped by Content. Each engine is
   * enumerated in isolation: if one throws (API drift between IDE versions, a threading assertion,
   * a remote-dev/split-mode frontend), we log it and still return whatever the other engine found,
   * rather than letting one failure zero out terminal detection entirely.
   */
  private fun targets(): List<Target> {
    val reworked = try {
      TerminalToolWindowTabsManager.getInstance(project).tabs.map { ReworkedTarget(it) }
    } catch (e: Throwable) {
      if (e is ControlFlowException || e is CancellationException) throw e
      thisLogger().warn("avec-moi: reworked-terminal enumeration failed; falling back to legacy only", e)
      emptyList()
    }
    val seen = reworked.mapTo(HashSet()) { it.content }
    val legacy = try {
      terminalContents()
        .filter { it !in seen }
        .mapNotNull { c -> legacyWidgetForContent(c)?.let { LegacyTarget(c, it) } }
    } catch (e: Throwable) {
      if (e is ControlFlowException || e is CancellationException) throw e
      thisLogger().warn("avec-moi: legacy-terminal enumeration failed", e)
      emptyList()
    }
    val all = reworked + legacy
    val log = thisLogger()
    if (log.isDebugEnabled) {
      log.debug("avec-moi terminal detection: reworked=${reworked.size}, legacy=${legacy.size}, names=${all.map { it.name }}")
    }
    // Visible (INFO) breadcrumb for the "detected nothing though tabs exist" failure — the split-tab
    // class of bug. Deduped so the sub-second update() polling doesn't spam idea.log.
    if (all.isEmpty()) {
      val contents = terminalContents()
      if (contents.isNotEmpty()) {
        val mgr = runCatching { TerminalToolWindowManager.getInstance(project) }.getOrNull()
        val diag = "tabs=${contents.size} classicWidgets=${runCatching { mgr?.widgets?.size }.getOrNull()} " +
          "terminalWidgets=${runCatching { mgr?.terminalWidgets?.size }.getOrNull()} reworkedTabs=${reworked.size}"
        if (diag != lastEmptyDiag) {
          lastEmptyDiag = diag
          log.info(
            "avec-moi: detected no terminal though the Terminal tool window has tabs ($diag) — " +
              "likely an un-enumerable layout (e.g. a split tab). Please report this line.",
          )
        }
      }
    }
    return all
  }

  private fun terminalContents(): List<Content> =
    ToolWindowManager.getInstance(project).getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
      ?.contentManager?.contents?.toList().orEmpty()

  /**
   * The classic-terminal widget for a tab. findWidgetByContent works for a single-pane tab but returns
   * null once the tab is SPLIT (the Content then holds several widgets, none the canonical one) — which
   * silently dropped split tabs from detection. Fall back to matching by UI ancestry: any pane whose
   * component lives under this tab's content, preferring the one that currently has focus. Panes are
   * gathered from BOTH registries — getWidgets() (classic JBTerminalWidget, itself a component;
   * bridged via asNewWidget()) and getTerminalWidgets() — since classic panes surface in the former.
   */
  private fun legacyWidgetForContent(content: Content): TerminalWidget? {
    TerminalToolWindowManager.findWidgetByContent(content)?.let { return it }
    val mgr = TerminalToolWindowManager.getInstance(project)
    val candidates: List<Pair<Component, TerminalWidget>> =
      mgr.widgets.map { (it as Component) to it.asNewWidget() } +
        mgr.terminalWidgets.map { (it.component as Component) to it }
    val inTab = candidates.filter { SwingUtilities.isDescendingFrom(it.first, content.component) }
    val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
    return (inTab.firstOrNull { focusOwner != null && SwingUtilities.isDescendingFrom(focusOwner, it.first) }
      ?: inTab.firstOrNull())?.second
  }

  /** Display names of all terminal tabs, for the tool-window dropdown and action gating. */
  fun tabNames(): List<String> = targets().map { it.name }

  fun setPreferred(name: String?) {
    preferredName = name
  }

  fun preferredName(): String? = preferredName

  /** The pinned tab if it still exists, else the selected terminal tab, else the first. */
  private fun target(): Target? {
    val targets = targets()
    if (targets.isEmpty()) return null
    preferredName?.let { name -> targets.firstOrNull { it.name == name }?.let { return it } }
    val selected = ToolWindowManager.getInstance(project)
      .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)?.contentManager?.selectedContent
    return targets.firstOrNull { it.content == selected } ?: targets.first()
  }

  fun targetName(): String? = target()?.name

  /** Inject [text] into the target terminal and submit it. Returns false if there's no terminal. */
  fun send(text: String): Boolean {
    val target = target() ?: return false
    if (!target.send(text)) return false
    when (project.service<AvecMoiSettingsService>().terminalOnSend) {
      TerminalRevealBehavior.SHOW -> revealTerminal(target.content, focus = false)
      TerminalRevealBehavior.SHOW_AND_FOCUS -> revealTerminal(target.content, focus = true)
      TerminalRevealBehavior.NONE -> Unit
    }
    return true
  }

  /** Show the terminal tool window and select [content]. When [focus] is false, keyboard focus stays put. */
  private fun revealTerminal(content: Content, focus: Boolean) {
    val toolWindow = ToolWindowManager.getInstance(project)
      .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID) ?: return
    toolWindow.activate({ toolWindow.contentManager.setSelectedContent(content, focus) }, focus)
  }
}
