package net.sjrx.intellij.plugins.avecmoi

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JList
import javax.swing.ListSelectionModel

/** The avec-moi tool window: shows the current handoff ring and lets the human walk it. */
class AvecMoiToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = AvecMoiToolWindowPanel(project)
    val content = ContentFactory.getInstance().createContent(panel, "", false)
    toolWindow.contentManager.addContent(content)

    // Live refresh whenever the ring changes (e.g. Claude calls register_handoffs).
    project.messageBus.connect(toolWindow.disposable).subscribe(
      AvecMoiRingService.TOPIC,
      AvecMoiRingListener { ApplicationManager.getApplication().invokeLater { panel.reload() } },
    )
  }
}

private class AvecMoiToolWindowPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

  private val model = CollectionListModel<AvecMoiRingService.Handoff>()

  private val list = JBList(model).apply {
    selectionMode = ListSelectionModel.SINGLE_SELECTION
    emptyText.text = "No handoffs yet — Claude registers them as you work."
    cellRenderer = object : ColoredListCellRenderer<AvecMoiRingService.Handoff>() {
      override fun customizeCellRenderer(
        list: JList<out AvecMoiRingService.Handoff>,
        value: AvecMoiRingService.Handoff,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
      ) {
        append("${index + 1}. ")
        append(value.note.ifBlank { "(no note)" })
        append(
          "   ${value.file.substringAfterLast('/')}:${value.line}",
          SimpleTextAttributes.GRAYED_ATTRIBUTES,
        )
      }
    }
    addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) {
        if (e.clickCount == 2) navigateTo(selectedValue ?: return)
      }
    })
  }

  init {
    toolbar = createToolbar().component
    setContent(JBScrollPane(list))
    reload()
  }

  fun reload() {
    val ring = project.service<AvecMoiRingService>()
    model.replaceAll(ring.all())
    val idx = ring.cursorIndex()
    if (idx in 0 until model.size) list.selectedIndex = idx
  }

  private fun navigateTo(handoff: AvecMoiRingService.Handoff) {
    AvecMoiNavigation.openAt(project, handoff.file, handoff.line)
  }

  private fun createToolbar(): ActionToolbar {
    val group = DefaultActionGroup().apply {
      add(NavAction("Previous Handoff", AllIcons.Actions.Back, forward = false))
      add(NavAction("Next Handoff", AllIcons.Actions.Forward, forward = true))
      add(RefreshAction())
    }
    return ActionManager.getInstance().createActionToolbar("AvecMoiRing", group, true).also {
      it.targetComponent = list
    }
  }

  private inner class NavAction(text: String, icon: Icon, private val forward: Boolean) :
    AnAction(text, null, icon), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
      e.presentation.isEnabled = !project.service<AvecMoiRingService>().isEmpty()
    }
    override fun actionPerformed(e: AnActionEvent) {
      val ring = project.service<AvecMoiRingService>()
      val handoff = (if (forward) ring.next() else ring.prev()) ?: return
      navigateTo(handoff)
      val idx = ring.cursorIndex()
      if (idx in 0 until model.size) list.selectedIndex = idx
    }
  }

  private inner class RefreshAction :
    AnAction("Refresh", "Reload the handoff list", AllIcons.Actions.Refresh), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) = reload()
  }
}
