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
      "I'm stuck on this — give me a hint, don't implement it for me: $file lines $startLine-$endLine."
    } else {
      val line = editor.caretModel.logicalPosition.line + 1
      "I'm stuck here — give me a hint, don't implement it for me: $file:$line."
    }

    if (!AvecMoiTerminal.send(project, message)) {
      thisLogger().warn("avec-moi: no terminal to send hint request to")
    }
  }
}
