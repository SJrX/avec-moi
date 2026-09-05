package net.sjrx.intellij.plugins.avecmoi

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager

/**
 * "Ask for a Hint": the human is stuck and wants a nudge — NOT for the agent to implement it. Saves
 * the current file and sends the agent a reference to where the human is (cursor or selection),
 * letting it read the freshly-saved region. Uses the active editor (via FileEditorManager) so it
 * works from the tool-window toolbar too, not only when the editor has focus.
 */
class AskForHintAction : AnAction() {

  init {
    templatePresentation.icon = AllIcons.Actions.IntentionBulb
  }

  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
    val doc = editor.document

    // Save everything so the agent (which may run git) sees the current state, not just this file.
    FileDocumentManager.getInstance().saveAllDocuments()
    val file = FileDocumentManager.getInstance().getFile(doc)?.path ?: return

    val selection = editor.selectionModel
    val message = if (selection.hasSelection()) {
      val startLine = doc.getLineNumber(selection.selectionStart) + 1
      val endLine = doc.getLineNumber(selection.selectionEnd) + 1
      hintMessage(file, startLine to endLine, caretLine = 0)
    } else {
      hintMessage(file, selectionLines = null, caretLine = editor.caretModel.logicalPosition.line + 1)
    }

    if (!AvecMoiTerminal.send(project, message)) {
      thisLogger().warn("avec-moi: no terminal to send hint request to")
    }
  }

  companion object {
    /**
     * The hint request the agent receives: a pointer to where the human is stuck (a selected line
     * range, else the caret line) plus the explicit "don't implement it for me" guardrail that makes
     * this a hint rather than a handoff. Pure, so the reference formatting is unit-testable.
     */
    fun hintMessage(file: String, selectionLines: Pair<Int, Int>?, caretLine: Int): String =
      if (selectionLines != null) {
        "I'm stuck on this — give me a hint, don't implement it for me: " +
          "$file lines ${selectionLines.first}-${selectionLines.second}."
      } else {
        "I'm stuck here — give me a hint, don't implement it for me: $file:$caretLine."
      }
  }
}
