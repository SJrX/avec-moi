package net.sjrx.intellij.plugins.avecmoi

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.terminal.actions.TerminalActionUtil
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * "Copy + Unwrap": like the terminal's Copy, but strips the hard soft-wrap breaks agents bake into
 * long output lines (see [AvecMoiUnwrap]) before putting the text on the clipboard.
 *
 * Reads the selection the same way the reworked terminal's own copy action does — the terminal output
 * is a real [com.intellij.openapi.editor.Editor], so its [com.intellij.openapi.editor.SelectionModel]
 * carries the selected text. If there's no editor selection (e.g. the classic engine, whose selection
 * isn't editor-based), it falls back to unwrapping whatever's already on the clipboard, so "copy first,
 * then Copy + Unwrap" still works there.
 */
class AvecMoiUnwrapCopyAction : AnAction() {

  init {
    templatePresentation.icon = AllIcons.Actions.Copy
  }

  override fun getActionUpdateThread() = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    // Mirror the sibling Copy action: shown only when there's a terminal selection to act on. The
    // clipboard fallback in actionPerformed is for the shortcut/classic path, not menu enablement —
    // reading the system clipboard on every menu update would be needlessly heavy.
    e.presentation.isEnabledAndVisible = terminalEditor(e)?.selectionModel?.hasSelection() == true
  }

  override fun actionPerformed(e: AnActionEvent) {
    val text = terminalEditor(e)?.selectionModel?.selectedText?.takeIf { it.isNotEmpty() }
      ?: clipboardText()
      ?: return
    CopyPasteManager.getInstance().setContents(StringSelection(AvecMoiUnwrap.unwrap(text)))
  }

  /**
   * The editor backing the terminal under the cursor. The reworked terminal's context-menu data
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
