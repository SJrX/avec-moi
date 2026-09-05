package net.sjrx.intellij.plugins.avecmoi

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager

/**
 * "Step Done": tells the agent the human finished their turn. Injects a canned line into the target
 * terminal session and submits it.
 */
class StepDoneAction : AnAction() {

  init {
    templatePresentation.icon = AllIcons.Actions.Commit
  }

  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    // Flush the human's edits to disk first — the IDE doesn't autosave while it keeps focus, so the
    // agent's git status/diff would otherwise not see the work being handed back for review.
    FileDocumentManager.getInstance().saveAllDocuments()
    if (!AvecMoiTerminal.send(project, CANNED_STEP_DONE)) {
      thisLogger().warn("avec-moi: no terminal to send Step Done to")
    }
  }

  companion object {
    // TODO(avec-moi): make the canned vocabulary configurable / multiple buttons.
    private const val CANNED_STEP_DONE = "Step done — review my implementation"
  }
}
