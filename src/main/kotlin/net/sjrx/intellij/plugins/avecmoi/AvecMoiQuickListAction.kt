package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.popup.JBPopupFactory

/**
 * Pops up the Avec Moi quick menu — handoff navigation plus the agent-signal actions — as an
 * action-group popup, the same style as "Refactor This" (Ctrl+Alt+Shift+T). Each row shows the
 * action's own keymap shortcut, so this doubles as a shortcut cheat-sheet. Bound to Alt+Shift+A.
 */
class AvecMoiQuickListAction : AnAction() {

  override fun getActionUpdateThread() = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val group = ActionManager.getInstance().getAction(GROUP_ID) as? ActionGroup ?: return
    JBPopupFactory.getInstance().createActionGroupPopup(
      "Avec Moi",
      group,
      e.dataContext,
      JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
      /* showDisabledActions = */ true, // keep Next/Previous visible (greyed) when there are no handoffs
    ).showInBestPositionFor(e.dataContext)
  }

  companion object {
    private const val GROUP_ID = "avecmoi.QuickListGroup"
  }
}
