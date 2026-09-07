package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager

/**
 * Step to the next / previous handoff in the active session's ring and jump the editor there. The
 * ring skips completed handoffs and wraps around (see [AvecMoiRingService.next]/[AvecMoiRingService.prev]).
 * One implementation, used by both the tool-window toolbar buttons and the menu/keymap actions, so
 * the behaviour can't drift. Also shows the handoff's note in the status bar and highlights its row
 * in the tool window when that window is open.
 */
abstract class HandoffNavAction(private val forward: Boolean) : AnAction() {

  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = e.project
    e.presentation.isEnabled = project != null && !project.service<AvecMoiRingService>().isEmpty()
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val ring = project.service<AvecMoiRingService>()
    val handoff = (if (forward) ring.next() else ring.prev()) ?: return

    val line = runReadActionBlocking { handoff.line }
    if (!AvecMoiNavigation.openAt(project, handoff.file, line)) {
      thisLogger().warn("avec-moi: handoff file not found: ${handoff.file}")
      return
    }
    if (handoff.note.isNotBlank()) {
      WindowManager.getInstance().getStatusBar(project)?.info = "Avec Moi ▸ ${handoff.note}"
    }
    highlightInToolWindow(project, ring.cursorIndex())
    // Navigation moves the ring cursor but isn't a ring mutation, so nudge the editor inlays too
    // (matters for "current handoff only" mode).
    project.service<AvecMoiInlayService>().refreshAll()
  }

  /** Select the landed handoff's row in the tool window, if it's currently open. Best-effort. */
  private fun highlightInToolWindow(project: Project, index: Int) {
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Avec Moi") ?: return
    val panel = toolWindow.contentManager.selectedContent?.component as? AvecMoiToolWindowPanel ?: return
    panel.selectActiveHandoff(index)
  }
}

class NextHandoffAction : HandoffNavAction(forward = true) {
  init { templatePresentation.icon = com.intellij.icons.AllIcons.Vcs.Arrow_right }
}

class PreviousHandoffAction : HandoffNavAction(forward = false) {
  init { templatePresentation.icon = com.intellij.icons.AllIcons.Vcs.Arrow_left }
}
