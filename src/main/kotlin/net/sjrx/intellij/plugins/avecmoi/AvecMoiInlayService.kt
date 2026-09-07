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
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.Icon

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
      editor.inlayModel.addBlockElement(offset, false, true, 0, NoteInlayRenderer(handoff))
        ?.let { inlays.add(it) }
    }
  }

  /** Live-update the inlay(s) for [handoff] (e.g. its note was edited in the pane) — no flicker. */
  fun noteChanged(handoff: AvecMoiRingService.Handoff) = onEdt {
    inlays.filter { (it.renderer as? NoteInlayRenderer)?.handoff === handoff }.forEach { it.update() }
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

  /**
   * Paints the note (multi-line) above the line, styled like an inlay hint: a rounded hint-colored
   * chip, the balloon icon on the first line, italic hint text, dimmed when done. Reads the handoff's
   * note live so an edit only needs Inlay.update(), not a recreate. The icon avoids missing-glyph
   * boxes for a text marker.
   */
  private class NoteInlayRenderer(val handoff: AvecMoiRingService.Handoff) : EditorCustomElementRenderer {
    private val icon = ICON
    private val pad = JBUI.scale(5)
    private val gap = JBUI.scale(4)

    private fun lines() = handoff.note.split("\n").ifEmpty { listOf("") }
    private fun font(editor: Editor) = editor.colorsScheme.getFont(EditorFontType.ITALIC)

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
      val fm = inlay.editor.contentComponent.getFontMetrics(font(inlay.editor))
      val textWidth = lines().maxOfOrNull { fm.stringWidth(it) } ?: 0
      return pad + icon.iconWidth + gap + textWidth + pad
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = inlay.editor.lineHeight * lines().size

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
      val editor = inlay.editor
      val g2 = g as Graphics2D
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      val attrs = editor.colorsScheme.getAttributes(AvecMoiColors.NOTE_INLAY)
      val fg = attrs?.foregroundColor ?: NOTE_FG_FALLBACK
      val lines = lines()
      val lineHeight = editor.lineHeight

      attrs?.backgroundColor?.let { bg ->
        g2.color = bg
        g2.fillRoundRect(targetRegion.x, targetRegion.y, calcWidthInPixels(inlay), targetRegion.height, JBUI.scale(8), JBUI.scale(8))
      }
      icon.paintIcon(editor.contentComponent, g2, targetRegion.x + pad, targetRegion.y + (lineHeight - icon.iconHeight) / 2)

      g2.font = font(editor)
      g2.color = if (handoff.done) UIUtil.getInactiveTextColor() else fg
      val ascent = g2.fontMetrics.ascent
      val textX = targetRegion.x + pad + icon.iconWidth + gap
      lines.forEachIndexed { i, line ->
        g2.drawString(line, textX, targetRegion.y + i * lineHeight + ascent)
      }
    }

    companion object {
      // Amber fallback for schemes that don't pick up our bundled defaults (light / dark), echoing
      // the IDE's ignored / non-project file foreground.
      private val NOTE_FG_FALLBACK = JBColor(Color(0x8C, 0x4F, 0x00), Color(0xD6, 0x9A, 0x6B))

      // The avec-moi plugin icon (40x40) scaled down to a marker size for the inlay.
      private val ICON: Icon = IconLoader.getIcon("/META-INF/pluginIcon.svg", NoteInlayRenderer::class.java).let {
        IconUtil.scale(it, null, JBUI.scale(15).toFloat() / it.iconWidth)
      }
    }
  }
}
