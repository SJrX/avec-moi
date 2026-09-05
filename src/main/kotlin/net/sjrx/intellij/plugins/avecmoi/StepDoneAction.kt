package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.thisLogger

/**
 * "Step Done": tells the agent the human finished their turn, without leaving the editor. Injects a
 * canned line into the running agent terminal session and submits it.
 */
class StepDoneAction : AnAction() {

  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    if (!AvecMoiTerminal.send(project, CANNED_STEP_DONE)) {
      thisLogger().warn("avec-moi: no terminal to send Step Done to")
    }
  }

  companion object {
    // TODO(avec-moi): make the canned vocabulary configurable / multiple buttons.
    private const val CANNED_STEP_DONE = "Step done — review my implementation"
  }
}
