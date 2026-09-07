package net.sjrx.intellij.plugins.avecmoi

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project

/**
 * "Do This Step": the human hands the current step back to the agent to implement, rather than doing
 * it themselves. Points at the editor selection if there is one, otherwise the handoff they're parked
 * on (the ring cursor). Disabled when there's no referent or no terminal to send to.
 */
class DoThisStepAction : AnAction() {

  init {
    templatePresentation.icon = AllIcons.Diff.MagicResolveToolbar
  }

  override fun getActionUpdateThread() = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    val project = e.project
    // Needs an agent, an active session (the loop is running), and a target (selection or the
    // handoff you're parked on) to hand back.
    e.presentation.isEnabled = project != null &&
      project.service<AvecMoiTerminalService>().tabs().isNotEmpty() &&
      project.service<AvecMoiRingService>().activeId() != null &&
      referentMessage(project) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    // Save first so the agent (which may run git) sees the current state before taking over.
    FileDocumentManager.getInstance().saveAllDocuments()
    val message = referentMessage(project) ?: return
    if (!AvecMoiTerminal.send(project, message)) {
      thisLogger().warn("avec-moi: no terminal to send 'do this step' to")
    }
  }

  /** The step message for the current context — selection first, then the current handoff — or null. */
  private fun referentMessage(project: Project): String? {
    val editor = FileEditorManager.getInstance(project).selectedTextEditor
    val selection = editor?.selectionModel
    if (editor != null && selection != null && selection.hasSelection()) {
      val doc = editor.document
      val file = FileDocumentManager.getInstance().getFile(doc)?.path
      if (file != null) {
        val start = doc.getLineNumber(selection.selectionStart) + 1
        val end = doc.getLineNumber(selection.selectionEnd) + 1
        return stepMessage(file, start, end, note = null)
      }
    }
    val ring = project.service<AvecMoiRingService>()
    return runReadActionBlocking {
      val handoff = ring.all().getOrNull(ring.cursorIndex()) ?: return@runReadActionBlocking null
      stepMessage(handoff.file, handoff.line, handoff.line, handoff.note)
    }
  }

  companion object {
    /**
     * The "you implement this one" request handed to the agent: a location (a selected line range,
     * else the handoff's single line) plus the handoff note when there is one. Pure, so it's
     * unit-testable independent of the editor/ring.
     */
    fun stepMessage(file: String, startLine: Int, endLine: Int, note: String?): String {
      val where = if (startLine == endLine) "$file:$startLine" else "$file lines $startLine-$endLine"
      val noteSuffix = note?.takeIf { it.isNotBlank() }?.let { " — “$it”" } ?: ""
      return "Please implement this step yourself instead of handing it back to me: $where$noteSuffix."
    }
  }
}
