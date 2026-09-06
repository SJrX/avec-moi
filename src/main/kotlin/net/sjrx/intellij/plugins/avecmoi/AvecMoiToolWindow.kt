package net.sjrx.intellij.plugins.avecmoi

import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ide.DefaultTreeExpander
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.LayeredIcon
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** The Avec Moi tool window: sessions grouped Active / Open / Historical, each a tree of handoffs. */
class AvecMoiToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = AvecMoiToolWindowPanel(project)
    val content = ContentFactory.getInstance().createContent(panel, "", false)
    toolWindow.contentManager.addContent(content)

    project.messageBus.connect(toolWindow.disposable).subscribe(
      AvecMoiRingService.TOPIC,
      AvecMoiRingListener { ApplicationManager.getApplication().invokeLater { panel.reload() } },
    )

    // The connection indicator changes without user interaction, so poll the toolbar to refresh it.
    val timer = Timer(2000) { panel.refreshToolbar() }.apply { isRepeats = true; start() }
    Disposer.register(toolWindow.disposable, Disposable { timer.stop() })

    // Keep displayed line numbers live as the human edits (the renderer reads live RangeMarkers).
    EditorFactory.getInstance().eventMulticaster.addDocumentListener(
      object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) = panel.refreshLines()
      },
      toolWindow.disposable,
    )
  }
}

internal class AvecMoiToolWindowPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

  /** Header row marking one of the three session groups. */
  private class Group(val label: String, val icon: Icon)

  /** A handoff's note (distinguishes note child nodes from handoff nodes in the renderer). */
  private class Note(val text: String)

  private val root = DefaultMutableTreeNode()
  private val treeModel = DefaultTreeModel(root)
  private val doneAttr = SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, JBColor.GRAY)
  private var activeSessionId: String? = null

  private val statusHere = AllIcons.RunConfigurations.TestState.Green2
  private val statusElsewhere = AllIcons.RunConfigurations.TestState.Yellow2
  private val statusRed = AllIcons.RunConfigurations.TestState.Red2
  private var toolbarRef: ActionToolbar? = null

  private val tree = Tree(treeModel).apply {
    isRootVisible = false
    showsRootHandles = true
    cellRenderer = HandoffCellRenderer()
    addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) {
        if (e.clickCount == 2) onDoubleClick()
      }
    })
  }

  init {
    toolbar = buildToolbar()
    setContent(JBScrollPane(tree))
    PopupHandler.installFollowingSelectionTreePopup(
      tree,
      DefaultActionGroup().apply {
        add(SetActiveAction())
        add(RenameSessionAction())
        add(CompleteSessionAction())
        addSeparator()
        add(DeleteSessionAction())
      },
      "AvecMoiSessionPopup",
    )
    reload()
  }

  fun reload() {
    val ring = project.service<AvecMoiRingService>()
    activeSessionId = ring.activeId()
    val sessions = ring.listSessions()
    val active = sessions.firstOrNull { it.id == activeSessionId && !it.historical }
    val open = sessions.filter { !it.historical && it.id != activeSessionId }
    val historical = sessions.filter { it.historical }

    root.removeAllChildren()
    val activeGroup = DefaultMutableTreeNode(Group("Active Session", AllIcons.Nodes.Favorite))
    active?.let { activeGroup.add(sessionNode(it)) }
    root.add(activeGroup)
    DefaultMutableTreeNode(Group("Open Sessions", AllIcons.Nodes.ModuleGroup)).also { g ->
      open.forEach { g.add(sessionNode(it)) }
      root.add(g)
    }
    DefaultMutableTreeNode(Group("Historical Sessions", AllIcons.Vcs.History)).also { g ->
      historical.forEach { g.add(sessionNode(it)) }
      root.add(g)
    }

    treeModel.reload()
    expandDefault()
    selectActiveHandoff(ring.cursorIndex())
  }

  /** Repaint so the renderer re-reads live marker line numbers after the human edits. */
  fun refreshLines() {
    tree.repaint()
  }

  /** Re-evaluate toolbar actions (drives the live agent-connection indicator). */
  fun refreshToolbar() {
    toolbarRef?.updateActionsAsync()
  }

  private fun sessionNode(session: AvecMoiRingService.Session): DefaultMutableTreeNode {
    val node = DefaultMutableTreeNode(session)
    for (handoff in session.handoffs) {
      val hn = DefaultMutableTreeNode(handoff)
      hn.add(DefaultMutableTreeNode(Note(handoff.note)))
      node.add(hn)
    }
    return node
  }

  /** Expand group headers + the active session, with todo handoffs open and done ones collapsed. */
  private fun expandDefault() {
    for (i in 0 until root.childCount) {
      tree.expandPath(TreePath((root.getChildAt(i) as DefaultMutableTreeNode).path))
    }
    val sn = activeSessionNode() ?: return
    tree.expandPath(TreePath(sn.path))
    for (j in 0 until sn.childCount) {
      val hn = sn.getChildAt(j) as DefaultMutableTreeNode
      if ((hn.userObject as? AvecMoiRingService.Handoff)?.done == false) {
        tree.expandPath(TreePath(hn.path))
      }
    }
  }

  /** The tree node for the active session (child of the always-first "Active Session" group). */
  private fun activeSessionNode(): DefaultMutableTreeNode? {
    if (root.childCount == 0) return null
    val activeGroup = root.getChildAt(0) as? DefaultMutableTreeNode ?: return null
    if (activeGroup.childCount == 0) return null
    return activeGroup.getChildAt(0) as? DefaultMutableTreeNode
  }

  internal fun selectActiveHandoff(index: Int) {
    val sn = activeSessionNode() ?: return
    if (index < 0 || index >= sn.childCount) return
    val node = sn.getChildAt(index) as DefaultMutableTreeNode
    val path = TreePath(node.path)
    tree.selectionPath = path
    tree.scrollPathToVisible(path)
  }

  private fun onDoubleClick() {
    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
    when (val obj = node.userObject) {
      is AvecMoiRingService.Handoff -> navigate(obj)
      is Note -> ((node.parent as? DefaultMutableTreeNode)?.userObject as? AvecMoiRingService.Handoff)?.let { navigate(it) }
      else -> {}
    }
  }

  /** Navigate to a handoff, reading its (marker-tracked) line under a read action. */
  private fun navigate(handoff: AvecMoiRingService.Handoff) {
    val line = ReadAction.compute<Int, RuntimeException> { handoff.line }
    AvecMoiNavigation.openAt(project, handoff.file, line)
  }

  private fun buildToolbar(): JComponent {
    val expander = DefaultTreeExpander(tree)
    val common = CommonActionsManager.getInstance()
    val am = ActionManager.getInstance()
    val mainGroup = DefaultActionGroup().apply {
      add(TerminalSelectorAction())
      addSeparator()
      am.getAction("avecmoi.StepDone")?.let { add(it) }
      am.getAction("avecmoi.DoThisStep")?.let { add(it) }
      am.getAction("avecmoi.AskForHint")?.let { add(it) }
      addSeparator()
      am.getAction("avecmoi.PreviousHandoff")?.let { add(it) }
      am.getAction("avecmoi.NextHandoff")?.let { add(it) }
      addSeparator()
      add(common.createExpandAllAction(expander, tree))
      add(common.createCollapseAllAction(expander, tree))
    }
    val mainToolbar = am.createActionToolbar("AvecMoiRing", mainGroup, true).also { it.targetComponent = tree }
    // Right-aligned status indicator in its own toolbar.
    val statusToolbar = am.createActionToolbar("AvecMoiStatus", DefaultActionGroup(ConnectionStatusAction()), true)
      .also { it.targetComponent = tree }
    toolbarRef = statusToolbar
    return JPanel(BorderLayout()).apply {
      add(mainToolbar.component, BorderLayout.CENTER)
      add(statusToolbar.component, BorderLayout.EAST)
    }
  }

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
        is Group -> {
          icon = obj.icon
          append(obj.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
          append("  ${node.childCount}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        is AvecMoiRingService.Session -> {
          icon = AllIcons.Nodes.Folder
          val done = obj.handoffs.count { it.done }
          val nameAttr =
            if (obj.id == activeSessionId) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
            else SimpleTextAttributes.REGULAR_ATTRIBUTES
          append(obj.name, nameAttr)
          val meta = (obj.branch?.let { "$it · " } ?: "") + "$done/${obj.handoffs.size}"
          append("   $meta", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        is AvecMoiRingService.Handoff -> {
          val order = ((node.parent as? DefaultMutableTreeNode)?.getIndex(node) ?: 0) + 1
          val iconAndLine = ReadAction.compute<Pair<Icon, Int>, RuntimeException> { fileIconFor(obj.file) to obj.line }
          icon = if (obj.done) LayeredIcon(iconAndLine.first, AllIcons.Nodes.DoneMark) else iconAndLine.first
          append("$order. ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
          append(obj.file.substringAfterLast('/'), if (obj.done) doneAttr else SimpleTextAttributes.REGULAR_ATTRIBUTES)
          append(":${iconAndLine.second}", if (obj.done) doneAttr else SimpleTextAttributes.GRAYED_ATTRIBUTES)
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

  private fun selectedSession(): AvecMoiRingService.Session? =
    (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? AvecMoiRingService.Session

  private inner class SetActiveAction : AnAction("Set Active", null, AllIcons.Nodes.Favorite), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
      val s = selectedSession()
      e.presentation.isEnabledAndVisible = s != null && !s.historical && s.id != activeSessionId
    }
    override fun actionPerformed(e: AnActionEvent) {
      selectedSession()?.let { project.service<AvecMoiRingService>().switchSession(it.id) }
    }
  }

  private inner class RenameSessionAction : AnAction("Rename Session…", null, AllIcons.Actions.Edit), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
      e.presentation.isEnabledAndVisible = selectedSession() != null
    }
    override fun actionPerformed(e: AnActionEvent) {
      val s = selectedSession() ?: return
      val name = Messages.showInputDialog(project, "Session name:", "Rename Session", null, s.name, null)
      if (!name.isNullOrBlank()) project.service<AvecMoiRingService>().renameSession(s.id, name)
    }
  }

  private inner class CompleteSessionAction : AnAction("Complete Session", null, AllIcons.Actions.Checked), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
      val s = selectedSession()
      e.presentation.isEnabledAndVisible = s != null && !s.historical
    }
    override fun actionPerformed(e: AnActionEvent) {
      selectedSession()?.let { project.service<AvecMoiRingService>().completeSession(it.id) }
    }
  }

  /** Permanently remove a historical (finished) session. Destructive, so it's confirmed and only
   *  offered on historical rows — completed sessions are the ones you'd prune. */
  private inner class DeleteSessionAction : AnAction("Delete Session", null, AllIcons.Actions.GC), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
      val s = selectedSession()
      e.presentation.isEnabledAndVisible = s != null && s.historical
    }
    override fun actionPerformed(e: AnActionEvent) {
      val s = selectedSession() ?: return
      val confirmed = Messages.showYesNoDialog(
        project,
        "Delete session “${s.name}” and its ${s.handoffs.size} handoff(s)? This can't be undone.",
        "Delete Session",
        Messages.getWarningIcon(),
      )
      if (confirmed == Messages.YES) project.service<AvecMoiRingService>().deleteSession(s.id)
    }
  }


  /**
   * Agent-connection indicator: green = an agent has talked to THIS project this session, yellow =
   * an agent is active but only in another project, grey = nothing has connected (click for how to).
   */
  private inner class ConnectionStatusAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    private fun currentState(): AvecMoiConnectionState = AvecMoiConnectionState.of(
      project.service<AvecMoiRingService>().isContacted(),
      service<AvecMoiMcpService>().anyContact(),
    )

    override fun update(e: AnActionEvent) {
      e.presentation.icon = when (currentState()) {
        AvecMoiConnectionState.HERE -> statusHere
        AvecMoiConnectionState.ELSEWHERE -> statusElsewhere
        AvecMoiConnectionState.NONE -> statusRed
      }
      e.presentation.text = when (currentState()) {
        AvecMoiConnectionState.HERE -> "Connected"
        AvecMoiConnectionState.ELSEWHERE -> "Connected (other project)"
        AvecMoiConnectionState.NONE -> "No Connection"
      }
    }

    override fun actionPerformed(e: AnActionEvent) {
      val msg = when (currentState()) {
        AvecMoiConnectionState.HERE -> "An agent has worked with Avec Moi in this project this session."
        AvecMoiConnectionState.ELSEWHERE -> "An agent is connected, but hasn't targeted THIS project yet.\n\n" +
          "Make sure it calls tools with this project's path (its cwd):\n  ${project.basePath}"
        AvecMoiConnectionState.NONE -> "No agent has contacted Avec Moi this session.\n\n" +
          "To connect, run:\n  claude mcp add --scope user --transport http avec-moi http://localhost:8282/mcp\n" +
          "then start (or restart) your agent session."
      }
      Messages.showInfoMessage(project, msg, "Avec Moi — Agent Connection")
    }
  }

  /** Dropdown to pin which terminal tab Avec Moi injects into (defaults to the selected one). */
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
}
