package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Graphics
import java.awt.Rectangle

/**
 * Floats handoff notes above their line in open editors as block inlays — visual only, so the
 * document (and line numbers) are untouched. Driven by the ring and the `editorNoteScope` setting
 * (ALL handoffs in the file / only the CURRENT one at the ring cursor / OFF). Navigation and the
 * note pane trigger a refresh; the tree topic covers registers/edits/marks.
 */
@Service(Service.Level.PROJECT)
class AvecMoiInlayService(private val project: Project) : Disposable {

  private val inlays = mutableListOf<Inlay<*>>()

  fun start() {
    project.messageBus.connect(this).subscribe(AvecMoiRingService.TOPIC, AvecMoiRingListener { refreshAll() })
    EditorFactory.getInstance().addEditorFactoryListener(
      object : EditorFactoryListener {
        override fun editorCreated(event: EditorFactoryEvent) {
          if (event.editor.project == project) onEdt { refreshEditor(event.editor) }
        }
      },
      this,
    )
    refreshAll()
  }

  fun refreshAll() = onEdt {
    EditorFactory.getInstance().allEditors.filter { it.project == project }.forEach { refreshEditor(it) }
  }

  /** Must run on the EDT. Rebuilds this editor's note inlays from the ring + current setting. */
  private fun refreshEditor(editor: Editor) {
    disposeInlaysFor(editor)
    val scope = project.service<AvecMoiSettingsService>().editorNoteScope
    if (scope == EditorNoteScope.OFF) return
    val editorPath = FileDocumentManager.getInstance().getFile(editor.document)?.path ?: return
    val ring = project.service<AvecMoiRingService>()
    val cursor = ring.cursorIndex()
    ring.all().forEachIndexed { i, handoff ->
      if (scope == EditorNoteScope.CURRENT && i != cursor) return@forEachIndexed
      if (handoff.note.isBlank()) return@forEachIndexed
      if (AvecMoiNavigation.resolveFile(project, handoff.file)?.path != editorPath) return@forEachIndexed
      val lineCount = editor.document.lineCount
      if (lineCount == 0) return@forEachIndexed
      val offset = editor.document.getLineStartOffset((handoff.line - 1).coerceIn(0, lineCount - 1))
      editor.inlayModel.addBlockElement(offset, false, true, 0, NoteInlayRenderer(handoff.note, handoff.done))
        ?.let { inlays.add(it) }
    }
  }

  private fun disposeInlaysFor(editor: Editor) {
    val gone = inlays.filter { it.editor === editor }
    gone.forEach { Disposer.dispose(it) }
    inlays.removeAll(gone.toSet())
  }

  private inline fun onEdt(crossinline block: () -> Unit) {
    val app = ApplicationManager.getApplication()
    if (app.isDispatchThread) block() else app.invokeLater { if (!project.isDisposed) block() }
  }

  override fun dispose() {
    inlays.forEach { Disposer.dispose(it) }
    inlays.clear()
  }

  /** Paints the (possibly multi-line) note above the line, greyed/italic; dimmer when done. */
  private class NoteInlayRenderer(note: String, private val done: Boolean) : EditorCustomElementRenderer {
    private val lines = note.split("\n").ifEmpty { listOf("") }

    private fun font(editor: Editor) = editor.colorsScheme.getFont(EditorFontType.ITALIC)

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
      val fm = inlay.editor.contentComponent.getFontMetrics(font(inlay.editor))
      return (lines.maxOfOrNull { fm.stringWidth("✎  $it") } ?: 0) + JBUI.scale(12)
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = inlay.editor.lineHeight * lines.size

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
      val editor = inlay.editor
      g.font = font(editor)
      g.color = if (done) UIUtil.getInactiveTextColor() else JBColor.GRAY
      val lineHeight = editor.lineHeight
      val ascent = g.fontMetrics.ascent
      lines.forEachIndexed { i, line ->
        val text = if (i == 0) "✎  $line" else "     $line"
        g.drawString(text, targetRegion.x + JBUI.scale(2), targetRegion.y + i * lineHeight + ascent)
      }
    }
  }
}
