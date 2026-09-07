package net.sjrx.intellij.plugins.avecmoi

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
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
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.util.Collections
import java.util.IdentityHashMap
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

  // Gutter chevrons that toggle each note (parallel to inlays; disposed together).
  private val gutterMarkers = mutableListOf<Pair<Editor, RangeHighlighter>>()

  // Notes the human collapsed, by identity — transient view state (not persisted). A collapsed note
  // renders as a single ellipsized line; its gutter chevron flips to point right.
  private val collapsed: MutableSet<AvecMoiRingService.Handoff> =
    Collections.newSetFromMap(IdentityHashMap())

  fun start() {
    project.messageBus.connect(this).subscribe(AvecMoiRingService.TOPIC, AvecMoiRingListener { refreshAll() })
    EditorFactory.getInstance().addEditorFactoryListener(
      object : EditorFactoryListener {
        override fun editorCreated(event: EditorFactoryEvent) {
          if (event.editor.project != project) return
          onEdt {
            refreshEditor(event.editor)
            // Re-measure the soft-wrapped notes when the editor's width changes (only width — ignore
            // pure scrolling), so wrapping tracks a resized/split editor.
            val editor = event.editor
            var lastWidth = editor.scrollingModel.visibleArea.width
            editor.scrollingModel.addVisibleAreaListener({ e ->
              if (e.newRectangle.width != lastWidth) {
                lastWidth = e.newRectangle.width
                inlays.filter { it.editor === editor }.forEach { it.update() }
              }
            }, this@AvecMoiInlayService)
          }
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
      val line = (handoff.line - 1).coerceIn(0, lineCount - 1)
      val offset = editor.document.getLineStartOffset(line)
      val isCollapsed = handoff in collapsed
      editor.inlayModel.addBlockElement(offset, false, true, 0, NoteInlayRenderer(handoff, isCollapsed))
        ?.let { inlays.add(it) }
      // A gutter chevron on the note's line, so the human can fold/unfold it like a code region.
      val marker = editor.markupModel.addLineHighlighter(null, line, HighlighterLayer.ADDITIONAL_SYNTAX)
      marker.gutterIconRenderer = NoteGutterIconRenderer(handoff, isCollapsed)
      gutterMarkers.add(editor to marker)
    }
  }

  /** Fold/unfold [handoff]'s note (from its gutter chevron). Transient; just re-renders. */
  fun toggleCollapsed(handoff: AvecMoiRingService.Handoff) = onEdt {
    if (!collapsed.remove(handoff)) collapsed.add(handoff)
    refreshAll()
  }

  /** Live-update the inlay(s) for [handoff] (e.g. its note was edited in the pane) — no flicker. */
  fun noteChanged(handoff: AvecMoiRingService.Handoff) = onEdt {
    inlays.filter { (it.renderer as? NoteInlayRenderer)?.handoff === handoff }.forEach { it.update() }
  }

  private fun disposeInlaysFor(editor: Editor) {
    val gone = inlays.filter { it.editor === editor }
    gone.forEach { Disposer.dispose(it) }
    inlays.removeAll(gone.toSet())
    val goneMarkers = gutterMarkers.filter { it.first === editor }
    goneMarkers.forEach { (ed, rh) -> ed.markupModel.removeHighlighter(rh) }
    gutterMarkers.removeAll(goneMarkers.toSet())
  }

  private inline fun onEdt(crossinline block: () -> Unit) {
    val app = ApplicationManager.getApplication()
    if (app.isDispatchThread) block() else app.invokeLater { if (!project.isDisposed) block() }
  }

  override fun dispose() {
    inlays.forEach { Disposer.dispose(it) }
    inlays.clear()
    gutterMarkers.forEach { (ed, rh) -> ed.markupModel.removeHighlighter(rh) }
    gutterMarkers.clear()
  }

  /** Gutter chevron that folds/unfolds a note — the platform draws it and routes the click for us. */
  private inner class NoteGutterIconRenderer(
    private val handoff: AvecMoiRingService.Handoff,
    private val isCollapsed: Boolean,
  ) : GutterIconRenderer() {
    override fun getIcon(): Icon = if (isCollapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown
    override fun getAlignment(): Alignment = Alignment.LEFT
    override fun getTooltipText(): String = if (isCollapsed) "Expand avec-moi note" else "Collapse avec-moi note"
    override fun getClickAction(): AnAction = object : AnAction() {
      override fun actionPerformed(e: AnActionEvent) = toggleCollapsed(handoff)
    }

    // Identity of the handoff + current state: the platform diff-updates gutter icons by equality.
    override fun equals(other: Any?): Boolean =
      other is NoteGutterIconRenderer && other.handoff === handoff && other.isCollapsed == isCollapsed
    override fun hashCode(): Int = System.identityHashCode(handoff) * 31 + isCollapsed.hashCode()
  }

  /**
   * Paints the note (multi-line) above the line, styled like an inlay hint: a rounded hint-colored
   * chip, the balloon icon on the first line, italic hint text, dimmed when done. Reads the handoff's
   * note live so an edit only needs Inlay.update(), not a recreate. The icon avoids missing-glyph
   * boxes for a text marker.
   */
  private class NoteInlayRenderer(
    val handoff: AvecMoiRingService.Handoff,
    private val collapsed: Boolean,
  ) : EditorCustomElementRenderer {
    private val icon = ICON
    private val pad = JBUI.scale(5)
    private val gap = JBUI.scale(4)

    private fun font(editor: Editor) = editor.colorsScheme.getFont(EditorFontType.ITALIC)

    /** What actually gets drawn: one ellipsized line when collapsed, else the soft-wrapped note. */
    private fun displayLines(inlay: Inlay<*>): List<String> =
      if (collapsed) listOf(collapsedLine(inlay)) else wrappedLines(inlay)

    /** The note's first non-blank line, ellipsized to one editor-width line, with "…" if there's more. */
    private fun collapsedLine(inlay: Inlay<*>): String {
      val fm = inlay.editor.contentComponent.getFontMetrics(font(inlay.editor))
      val budget = textWidthBudget(inlay.editor)
      val first = handoff.note.split("\n").firstOrNull { it.isNotBlank() }?.trim() ?: ""
      val hasMore = handoff.note.trim() != first // any content beyond that first line
      if (!hasMore && fm.stringWidth(first) <= budget) return first
      var s = first
      while (s.isNotEmpty() && fm.stringWidth("$s…") > budget) s = s.dropLast(1)
      return "$s…"
    }

    /**
     * The note split into display lines, soft-wrapped to the editor's visible width so the agent
     * needn't hard-wrap. The note's own newlines are honored as hard breaks (paragraphs, lists);
     * each such logical line is then word-wrapped to fit the remaining width after the icon+padding.
     * On a wide monitor this collapses to few lines; on a narrow one it wraps instead of overflowing.
     */
    private fun wrappedLines(inlay: Inlay<*>): List<String> {
      val fm = inlay.editor.contentComponent.getFontMetrics(font(inlay.editor))
      val budget = textWidthBudget(inlay.editor)
      val out = ArrayList<String>()
      for (logical in handoff.note.split("\n")) {
        if (logical.isBlank()) { out.add(logical); continue }
        val line = StringBuilder()
        for (word in logical.split(" ")) {
          val candidate = if (line.isEmpty()) word else "$line $word"
          if (line.isEmpty() || fm.stringWidth(candidate) <= budget) {
            line.setLength(0); line.append(candidate)
          } else {
            out.add(line.toString()); line.setLength(0); line.append(word)
          }
        }
        out.add(line.toString())
      }
      return out.ifEmpty { listOf("") }
    }

    /** Pixels available for note text: the visible editor width minus the icon, padding, and a margin. */
    private fun textWidthBudget(editor: Editor): Int {
      val chrome = pad + icon.iconWidth + gap + pad
      return (editor.scrollingModel.visibleArea.width - chrome - JBUI.scale(20)).coerceAtLeast(JBUI.scale(200))
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
      val fm = inlay.editor.contentComponent.getFontMetrics(font(inlay.editor))
      val textWidth = displayLines(inlay).maxOfOrNull { fm.stringWidth(it) } ?: 0
      return pad + icon.iconWidth + gap + textWidth + pad
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = inlay.editor.lineHeight * displayLines(inlay).size

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
      val editor = inlay.editor
      val g2 = g as Graphics2D
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      val key = if (handoff.done) AvecMoiColors.NOTE_INLAY_DONE else AvecMoiColors.NOTE_INLAY
      val attrs = editor.colorsScheme.getAttributes(key)
      val fg = attrs?.foregroundColor ?: if (handoff.done) NOTE_FG_DONE_FALLBACK else NOTE_FG_FALLBACK
      val lines = displayLines(inlay)
      val lineHeight = editor.lineHeight

      attrs?.backgroundColor?.let { bg ->
        g2.color = bg
        g2.fillRoundRect(targetRegion.x, targetRegion.y, calcWidthInPixels(inlay), targetRegion.height, JBUI.scale(8), JBUI.scale(8))
      }
      icon.paintIcon(editor.contentComponent, g2, targetRegion.x + pad, targetRegion.y + (lineHeight - icon.iconHeight) / 2)

      g2.font = font(editor)
      g2.color = fg
      val ascent = g2.fontMetrics.ascent
      val textX = targetRegion.x + pad + icon.iconWidth + gap
      lines.forEachIndexed { i, line ->
        g2.drawString(line, textX, targetRegion.y + i * lineHeight + ascent)
      }
    }

    companion object {
      // Foreground fallbacks for schemes that don't pick up our bundled defaults (light / dark):
      // todo = the ignored / non-project amber, done = the added green.
      private val NOTE_FG_FALLBACK = JBColor(Color(0x8C, 0x4F, 0x00), Color(0xD6, 0x9A, 0x6B))
      private val NOTE_FG_DONE_FALLBACK = JBColor(Color(0x06, 0x7D, 0x17), Color(0x73, 0xBD, 0x79))

      // The avec-moi plugin icon (40x40) scaled down to a marker size for the inlay.
      private val ICON: Icon = IconLoader.getIcon("/META-INF/pluginIcon.svg", NoteInlayRenderer::class.java).let {
        IconUtil.scale(it, null, JBUI.scale(15).toFloat() / it.iconWidth)
      }
    }
  }
}
