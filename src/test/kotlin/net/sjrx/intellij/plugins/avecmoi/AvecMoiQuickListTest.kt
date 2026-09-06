package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager

/**
 * The Alt+Shift+A quick-menu popup is built from the `avecmoi.QuickListGroup` action group. This
 * guards its contents and order — a typo in a <reference ref="…"> would silently drop an item from
 * the popup, which is easy to miss by eye.
 */
class AvecMoiQuickListTest : AbstractAvecMoiTest() {

  fun testTheQuickListTriggerActionIsRegistered() {
    assertNotNull(ActionManager.getInstance().getAction("avecmoi.QuickList"))
  }

  fun testTheQuickListGroupHoldsTheExpectedActionsInOrder() {
    val am = ActionManager.getInstance()
    val group = am.getAction("avecmoi.QuickListGroup") as ActionGroup

    // Separators resolve to a null id and drop out, leaving just the referenced actions.
    val ids = group.getChildren(null).mapNotNull { am.getId(it) }

    assertEquals(
      listOf(
        "avecmoi.NextHandoff",
        "avecmoi.PreviousHandoff",
        "avecmoi.StepDone",
        "avecmoi.DoThisStep",
        "avecmoi.AskForHint",
      ),
      ids,
    )
  }
}
