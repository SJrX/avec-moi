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

  /** One injectable terminal — a reworked tab or a classic widget (incl. a single pane of a split). */
  private sealed interface Target {
    val name: String

    /** The backing Content if this is a whole tab (reworked / single-pane classic); null for a bare
     *  widget (e.g. one pane of a SPLIT classic tab, which has no Content of its own). */
    val content: Content?

    /** Component to focus on reveal when there's no Content (a split pane). */
    val focusable: javax.swing.JComponent?

    /** Inject [text] and submit it. Returns false if the target can't currently accept input. */
    fun send(text: String): Boolean
  }

  private inner class ReworkedTarget(private val tab: TerminalToolWindowTab) : Target {
    override val name: String get() = tab.content.displayName ?: "Terminal"
    override val content: Content get() = tab.content
    override val focusable: javax.swing.JComponent? get() = null
    override fun send(text: String): Boolean {
      val view = tab.view ?: return false
      // Bracketed paste gives the TUI an explicit paste boundary, so the trailing Enter
      // (shouldExecute) submits cleanly instead of being swallowed when the agent's paste-detection
      // buffers a long line.
      view.createSendTextBuilder().useBracketedPasteMode().shouldExecute().send(text)
      return true
    }
  }

  /**
   * A classic-terminal widget, enumerated directly from getTerminalWidgets(). This is the reliable
   * source: splitting a tab EMPTIES the tool window's content list yet the panes stay in
   * getTerminalWidgets(), so a content-based enumeration misses split panes.
   */
  private inner class WidgetTarget(private val widget: TerminalWidget) : Target {
    // The STABLE tab label ("Local", "Local (2)", or a manual rename) — NOT buildTitle(), which prefers
    // the shell's application title (bash sets the window title to the prompt, e.g. sjr@host:~/dir).
    override val name: String get() = widget.terminalTitle.let { t ->
      t.userDefinedTitle?.takeIf { it.isNotBlank() } ?: t.defaultTitle?.takeIf { it.isNotBlank() } ?: "Terminal"
    }
    override val content: Content? get() = null
    override val focusable: javax.swing.JComponent? get() = widget.preferredFocusableComponent
    override fun send(text: String): Boolean {
      // Wrap in a bracketed-paste boundary then Enter, written straight to the PTY, so a long/multi-line
      // message lands as one paste and the CR submits.
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
   * Every terminal across both engines: reworked tabs (via [TerminalToolWindowTabsManager]) plus classic
   * widgets (via [TerminalToolWindowManager.getTerminalWidgets], which — unlike the content list — keeps
   * every pane of a SPLIT tab). Classic widgets living inside a reworked tab are dropped to avoid double-
   * counting. Each engine is enumerated in isolation so one failing (API drift, a threading assertion, a
   * remote/split frontend) still returns what the other found instead of zeroing detection.
   */
  private fun targets(): List<Target> {
    val reworked = try {
      TerminalToolWindowTabsManager.getInstance(project).tabs.map { ReworkedTarget(it) }
    } catch (e: Throwable) {
      if (e is ControlFlowException || e is CancellationException) throw e
      thisLogger().warn("avec-moi: reworked-terminal enumeration failed; falling back to classic only", e)
      emptyList()
    }
    val reworkedRoots = reworked.mapNotNull { it.content?.component }
    val classic = try {
      TerminalToolWindowManager.getInstance(project).terminalWidgets
        .filter { w -> reworkedRoots.none { SwingUtilities.isDescendingFrom(w.component, it) } }
        .map { WidgetTarget(it) }
    } catch (e: Throwable) {
      if (e is ControlFlowException || e is CancellationException) throw e
      thisLogger().warn("avec-moi: classic-terminal enumeration failed", e)
      emptyList()
    }
    val all = reworked + classic
    // Full detection breadcrumb, logged at INFO whenever it CHANGES (deduped, so polling doesn't spam).
    // Always-on so a baseline appears even when detection works — then the split vs unsplit lines can be
    // compared to see which source dropped the tab. If NO line ever appears, this code isn't running.
    val mgr = runCatching { TerminalToolWindowManager.getInstance(project) }.getOrNull()
    val tw = runCatching {
      ToolWindowManager.getInstance(project).getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
    }.getOrNull()
    val diag = "detected=${all.size} names=${all.map { it.name }} | toolWindow=${tw != null} " +
      "contents=${runCatching { tw?.contentManager?.contents?.size }.getOrNull()} " +
      "reworkedTabs=${reworked.size} " +
      "classicWidgets=${runCatching { mgr?.widgets?.size }.getOrNull()} " +
      "terminalWidgets=${runCatching { mgr?.terminalWidgets?.size }.getOrNull()}"
    if (diag != lastEmptyDiag) {
      lastEmptyDiag = diag
      thisLogger().info("avec-moi terminal detection: $diag")
    }
    return all
  }

  /** Display names of all terminal tabs, for the tool-window dropdown and action gating. */
  fun tabNames(): List<String> = targets().map { it.name }

  fun setPreferred(name: String?) {
    preferredName = name
  }

  fun preferredName(): String? = preferredName

  /** The pinned tab; else the pane the user is focused on; else the selected tab; else the first. */
  private fun target(): Target? {
    val targets = targets()
    if (targets.isEmpty()) return null
    preferredName?.let { name -> targets.firstOrNull { it.name == name }?.let { return it } }
    // Prefer the pane that actually has focus — matters for a split, where several panes share a tab.
    val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
    targets.firstOrNull { t -> t.focusable?.let { focusOwner != null && SwingUtilities.isDescendingFrom(focusOwner, it) } == true }
      ?.let { return it }
    val selected = ToolWindowManager.getInstance(project)
      .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)?.contentManager?.selectedContent
    return targets.firstOrNull { it.content != null && it.content == selected } ?: targets.first()
  }

  fun targetName(): String? = target()?.name

  /** Inject [text] into the target terminal and submit it. Returns false if there's no terminal. */
  fun send(text: String): Boolean {
    val target = target() ?: return false
    if (!target.send(text)) return false
    when (project.service<AvecMoiSettingsService>().terminalOnSend) {
      TerminalRevealBehavior.SHOW -> reveal(target, focus = false)
      TerminalRevealBehavior.SHOW_AND_FOCUS -> reveal(target, focus = true)
      TerminalRevealBehavior.NONE -> Unit
    }
    return true
  }

  /** Show the terminal tool window, select the target's tab if it has one, and focus its pane if asked. */
  private fun reveal(target: Target, focus: Boolean) {
    val toolWindow = ToolWindowManager.getInstance(project)
      .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID) ?: return
    toolWindow.activate({
      target.content?.let { toolWindow.contentManager.setSelectedContent(it, focus) }
      if (focus) target.focusable?.requestFocusInWindow()
    }, focus)
  }
}
