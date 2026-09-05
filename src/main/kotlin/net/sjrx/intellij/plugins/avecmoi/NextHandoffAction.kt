package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.wm.WindowManager

/**
 * Jumps the editor to the next handoff in the project's ring (circular), showing its note in the
 * status bar. Lines come from the ring's RangeMarkers, so they stay correct as you edit.
 */
class NextHandoffAction : AnAction() {

  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = e.project
    e.presentation.isEnabled = project != null && !project.service<AvecMoiRingService>().isEmpty()
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val handoff = project.service<AvecMoiRingService>().next() ?: return

    if (!AvecMoiNavigation.openAt(project, handoff.file, handoff.line)) {
      thisLogger().warn("avec-moi: handoff file not found: ${handoff.file}")
      return
    }

    if (handoff.note.isNotBlank()) {
      WindowManager.getInstance().getStatusBar(project)?.info = "Avec Moi ▸ ${handoff.note}"
    }
  }
}
