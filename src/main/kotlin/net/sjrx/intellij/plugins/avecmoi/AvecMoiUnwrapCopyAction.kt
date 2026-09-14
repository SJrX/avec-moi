package net.sjrx.intellij.plugins.avecmoi

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.terminal.JBTerminalWidget
import com.intellij.terminal.actions.TerminalActionUtil
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * "Copy + Unwrap": like the terminal's Copy, but strips the hard soft-wrap breaks agents bake into
 * long output lines (see [AvecMoiUnwrap]) before putting the text on the clipboard. Subclasses fix
 * what a broken line is rejoined with — nothing (URLs/paths) or a single space (prose) — since which
 * one is right depends on the selection and only the human knows.
 *
 * Reads the selection three ways, in order: the reworked terminal's editor (its output is a real
 * [Editor], read via the terminal's own [TerminalActionUtil.EDITOR_KEY]); the classic terminal's
 * [JBTerminalWidget.SELECTED_TEXT_DATA_KEY] (its selection isn't editor-based); and finally the
 * clipboard, so "copy first, then unwrap" works anywhere. The classic terminal's right-click menu is
 * a fixed platform-built list with no extension point, so there these actions are reachable via Find
 * Action rather than the context menu — the classic data key is what makes that work.
 */
abstract class AvecMoiUnwrapCopyActionBase(private val separator: String) : AnAction() {

  init {
    templatePresentation.icon = AllIcons.Actions.Copy
  }

  override fun getActionUpdateThread() = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    // Mirror the sibling Copy action: shown only when there's a terminal selection to act on. The
    // clipboard fallback in actionPerformed is for other invocation paths, not menu enablement —
    // reading the system clipboard on every menu update would be needlessly heavy.
    e.presentation.isEnabledAndVisible = terminalSelection(e) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val text = terminalSelection(e) ?: clipboardText() ?: return
    CopyPasteManager.getInstance().setContents(StringSelection(AvecMoiUnwrap.unwrap(text, separator)))
  }

  /** The current terminal selection: reworked (editor) first, then classic (data key). Null if none. */
  private fun terminalSelection(e: AnActionEvent): String? =
    terminalEditor(e)?.selectionModel?.selectedText?.takeIf { it.isNotEmpty() }
      ?: e.getData(JBTerminalWidget.SELECTED_TEXT_DATA_KEY)?.takeIf { it.isNotEmpty() }

  /**
   * The editor backing the reworked terminal under the cursor. That terminal's context-menu data
   * context exposes its editor ONLY under [TerminalActionUtil.EDITOR_KEY] (not [CommonDataKeys.EDITOR]
   * — its DataContextWrapper special-cases just that one key), which is why gating on the generic
   * editor key hid this action entirely. We read the terminal key first, exactly like the built-in
   * Terminal.CopySelectedText, and fall back to the generic key for any other host.
   */
  private fun terminalEditor(e: AnActionEvent): Editor? =
    e.getData(TerminalActionUtil.EDITOR_KEY) ?: e.getData(CommonDataKeys.EDITOR)

  /** The clipboard's current text, or null if empty / not text / unreadable. */
  private fun clipboardText(): String? =
    runCatching { CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor) as? String }
      .getOrNull()
      ?.takeIf { it.isNotEmpty() }
}

/** Copy + Unwrap joining broken lines directly, with no separator — best for URLs and paths. */
class AvecMoiUnwrapJoinCopyAction : AvecMoiUnwrapCopyActionBase("")

/** Copy + Unwrap joining broken lines with a single space — best for reflowing wrapped prose. */
class AvecMoiUnwrapSpaceCopyAction : AvecMoiUnwrapCopyActionBase(" ")
