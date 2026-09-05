package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager

/**
 * "Ask for a Hint": the human is stuck and wants a nudge — NOT for the agent to implement it (that
 * would defeat avec-moi). Saves the current file, then sends the agent a reference to where the
 * human is (cursor line, or the selected range), and lets the agent read the freshly-saved region.
 *
 * We send a location reference rather than pasting code: it's always current and sidesteps the
 * escaping/newline hazards of injecting multi-line content into a terminal.
 */
class AskForHintAction : AnAction() {

  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return
    val doc = editor.document

    // Save so the agent reads current content when it looks at the referenced spot.
    FileDocumentManager.getInstance().saveDocument(doc)

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
