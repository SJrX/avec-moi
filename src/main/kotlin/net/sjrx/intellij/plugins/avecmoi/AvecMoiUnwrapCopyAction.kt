package net.sjrx.intellij.plugins.avecmoi

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.terminal.frontend.view.TerminalView
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * "Copy + Unwrap": like the terminal's Copy, but strips the hard soft-wrap breaks agents bake into
 * long output lines (see [AvecMoiUnwrap]) before putting the text on the clipboard. Subclasses fix
 * what a broken line is rejoined with — nothing (URLs/paths) or a single space (prose) — since which
 * one is right depends on the selection and only the human knows.
 *
 * Reads the selection from the reworked terminal's public [TerminalView] (its text selection model),
 * falling back to a generic [Editor] selection and then the clipboard so "copy first, then unwrap"
 * still works. avec-moi targets the reworked terminal only; the classic engine isn't
 * supported.
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
    e.presentation.isEnabledAndVisible = !selectedText(e).isNullOrEmpty()
  }

  override fun actionPerformed(e: AnActionEvent) {
    val text = selectedText(e)?.takeIf { it.isNotEmpty() }
      ?: clipboardText()
      ?: return
    CopyPasteManager.getInstance().setContents(StringSelection(AvecMoiUnwrap.unwrap(text, separator)))
  }

  /**
   * The selected text in the reworked terminal under the cursor. That terminal's context-menu data
   * context does NOT expose its editor under [CommonDataKeys.EDITOR] (gating on that hid this action
   * entirely), and its own editor key is internal API, so we read the selection through the public
   * [TerminalView.DATA_KEY] it also publishes. Falls back to the generic editor for any other host.
   */
  private fun selectedText(e: AnActionEvent): String? {
    e.getData(TerminalView.DATA_KEY)?.let { view ->
      val selection = view.textSelectionModel.selection ?: return null
      return view.outputModels.active.value.getText(selection.startOffset, selection.endOffset).toString()
    }
    return e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText
  }

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
