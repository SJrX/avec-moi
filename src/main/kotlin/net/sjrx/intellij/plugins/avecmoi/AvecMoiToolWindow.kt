package net.sjrx.intellij.plugins.avecmoi

import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.LayeredIcon
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** The avec-moi tool window: the handoff ring as a file tree — filename:line, note beneath. */
class AvecMoiToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = AvecMoiToolWindowPanel(project)
    val content = ContentFactory.getInstance().createContent(panel, "", false)
    toolWindow.contentManager.addContent(content)

    project.messageBus.connect(toolWindow.disposable).subscribe(
      AvecMoiRingService.TOPIC,
      AvecMoiRingListener { ApplicationManager.getApplication().invokeLater { panel.reload() } },
    )

    // Keep displayed line numbers live as the human edits — the renderer reads the live
    // RangeMarker position, so a repaint is enough to reflect drift.
    EditorFactory.getInstance().eventMulticaster.addDocumentListener(
      object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) = panel.refreshLines()
      },
      toolWindow.disposable,
    )
  }
}

private class AvecMoiToolWindowPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

  private val root = DefaultMutableTreeNode()
  private val treeModel = DefaultTreeModel(root)
  private val tree = Tree(treeModel).apply {
    isRootVisible = false
    showsRootHandles = true
    emptyText.text = "No handoffs yet — the agent registers them as you work."
    cellRenderer = HandoffCellRenderer()
    addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) {
        if (e.clickCount == 2) selectedHandoff()?.let { AvecMoiNavigation.openAt(project, it.file, it.line) }
      }
    })
  }

  private val doneAttr = SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, JBColor.GRAY)

  init {
    toolbar = createToolbar().component
    setContent(JBScrollPane(tree))
    reload()
  }

  fun reload() {
    root.removeAllChildren()
    for (handoff in project.service<AvecMoiRingService>().all()) {
      val node = DefaultMutableTreeNode(handoff)
      node.add(DefaultMutableTreeNode(Note(handoff.note)))
      root.add(node)
    }
    treeModel.reload()
    // Expand todo handoffs (note visible); leave done ones collapsed to declutter.
    for (i in 0 until root.childCount) {
      val node = root.getChildAt(i) as DefaultMutableTreeNode
      if ((node.userObject as? AvecMoiRingService.Handoff)?.done == false) {
        tree.expandPath(TreePath(node.path))
      }
    }
    selectHandoff(project.service<AvecMoiRingService>().cursorIndex())
  }

  /** Repaint so the renderer re-reads live marker line numbers after the human edits. */
  fun refreshLines() {
    if (root.childCount > 0) tree.repaint()
  }

  private fun selectedHandoff(): AvecMoiRingService.Handoff? {
    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
    return node.userObject as? AvecMoiRingService.Handoff
      ?: (node.parent as? DefaultMutableTreeNode)?.userObject as? AvecMoiRingService.Handoff
  }

  private fun selectHandoff(index: Int) {
    if (index < 0 || index >= root.childCount) return
    val node = root.getChildAt(index) as DefaultMutableTreeNode
    val path = TreePath(node.path)
    tree.selectionPath = path
    tree.scrollPathToVisible(path)
  }

  private fun createToolbar(): ActionToolbar {
    val expander = DefaultTreeExpander(tree)
    val common = CommonActionsManager.getInstance()
    val am = ActionManager.getInstance()
    val group = DefaultActionGroup().apply {
      add(TerminalSelectorAction())
      addSeparator()
      am.getAction("avecmoi.StepDone")?.let { add(it) }
      am.getAction("avecmoi.AskForHint")?.let { add(it) }
      addSeparator()
      add(NavAction("Previous Handoff", AllIcons.Vcs.Arrow_left, forward = false))
      add(NavAction("Next Handoff", AllIcons.Vcs.Arrow_right, forward = true))
      addSeparator()
      add(common.createExpandAllAction(expander, tree))
      add(common.createCollapseAllAction(expander, tree))
    }
    return am.createActionToolbar("AvecMoiRing", group, true).also {
      it.targetComponent = tree
    }
  }

  /** Dropdown to pin which terminal tab avec-moi injects into (defaults to the selected one). */
  private inner class TerminalSelectorAction : ComboBoxAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
      val svc = project.service<AvecMoiTerminalService>()
      e.presentation.isEnabled = svc.tabs().isNotEmpty()
      e.presentation.text = "Terminal: " + (svc.targetName() ?: "none")
    }

    override fun createPopupActionGroup(button: JComponent): DefaultActionGroup {
      val svc = project.service<AvecMoiTerminalService>()
      return DefaultActionGroup().apply {
        for (tab in svc.tabs()) {
          val name = tab.content.displayName ?: "Terminal"
          add(object : AnAction(name), DumbAware {
            override fun actionPerformed(e: AnActionEvent) = svc.setPreferred(name)
          })
        }
      }
    }
  }

  /** Marker type so the renderer can tell a note child from a handoff node. */
  private class Note(val text: String)

  private inner class HandoffCellRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
      tree: JTree,
      value: Any?,
      selected: Boolean,
      expanded: Boolean,
      leaf: Boolean,
      row: Int,
      hasFocus: Boolean,
    ) {
      val node = value as? DefaultMutableTreeNode ?: return
      when (val obj = node.userObject) {
        is AvecMoiRingService.Handoff -> {
          val order = root.getIndex(node) + 1
          val fileIcon = fileIconFor(obj.file)
          icon = if (obj.done) LayeredIcon(fileIcon, AllIcons.Nodes.DoneMark) else fileIcon
          append("$order. ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
          append(obj.file.substringAfterLast('/'), if (obj.done) doneAttr else SimpleTextAttributes.REGULAR_ATTRIBUTES)
          append(":${obj.line}", if (obj.done) doneAttr else SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        is Note -> {
          icon = AllIcons.General.Balloon
          append(obj.text.ifBlank { "(no note)" }, SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES)
        }
        else -> {}
      }
    }
  }

  private fun fileIconFor(file: String): Icon {
    val vf = AvecMoiNavigation.resolveFile(project, file)
    return vf?.fileType?.icon ?: AllIcons.FileTypes.Unknown
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
      AvecMoiNavigation.openAt(project, handoff.file, handoff.line)
      selectHandoff(ring.cursorIndex())
    }
  }
}
