package net.sjrx.intellij.plugins.avecmoi

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
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

  // Notes the human collapsed, by identity — transient view state (not persisted). A collapsed note
  // renders as a single ellipsized line; its gutter chevron flips to point right.
  private val collapsed: MutableSet<AvecMoiRingService.Handoff> =
    Collections.newSetFromMap(IdentityHashMap())

  // Phrasebooks the human folded — separately from the note, so either can be put away on its own.
  private val phrasebookCollapsed: MutableSet<AvecMoiRingService.Handoff> =
    Collections.newSetFromMap(IdentityHashMap())

  private var started = false

  /** Subscribe to ring changes and editor creation. Idempotent. */
  fun start() {
    if (started) return
    started = true
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
  private fun refreshEditor(editor: Editor) = ReadAction.run<RuntimeException> {
    disposeInlaysFor(editor)
    val scope = project.service<AvecMoiSettingsService>().editorNoteScope
    if (scope == EditorNoteScope.OFF) return@run
    val editorPath = FileDocumentManager.getInstance().getFile(editor.document)?.path ?: return@run
    val lineCount = editor.document.lineCount
    if (lineCount == 0) return@run
    val ring = project.service<AvecMoiRingService>()
    val current = ring.currentHandoff()
    // Work handoffs honor the ALL/CURRENT scope. References are context — they float only when they're
    // the current (selected) item, so they don't clutter the editor the rest of the time.
    ring.all().forEach { handoff ->
      if (scope == EditorNoteScope.ALL || handoff === current) addNoteInlay(editor, editorPath, lineCount, handoff)
    }
    ring.references().forEach { if (it === current) addNoteInlay(editor, editorPath, lineCount, it) }
    // Inlays created before the editor is laid out measure against a stale (often too-small) width, so
    // their height can be wrong — leaving blank space after the note until something forces a re-measure.
    // Re-measure once on the next EDT cycle, when the viewport width is real.
    val fresh = inlays.filter { it.editor === editor }
    if (fresh.isNotEmpty()) ApplicationManager.getApplication().invokeLater {
      if (!project.isDisposed) fresh.forEach { if (it.isValid) it.update() }
    }
  }

  /** Add one note inlay for [handoff] above its line in [editor], if the note is set and the file matches. */
  private fun addNoteInlay(editor: Editor, editorPath: String, lineCount: Int, handoff: AvecMoiRingService.Handoff) {
    if (handoff.note.isBlank()) return
    if (AvecMoiNavigation.resolveFile(project, handoff.file)?.path != editorPath) return
    val offset = editor.document.getLineStartOffset((handoff.line - 1).coerceIn(0, lineCount - 1))
    val isCollapsed = handoff in collapsed
    val maxCols = project.service<AvecMoiSettingsService>().noteWrapMaxColumns
    // The renderer carries its own gutter chevron (calcGutterIconRenderer), so the fold arrow lands on
    // the note's own inlay row — not the code line below it.
    val gutter = FoldGutterIconRenderer(handoff, FoldTarget.NOTE, isCollapsed)
    val renderer = NoteInlayRenderer(handoff, isCollapsed, maxCols, gutter)
    editor.inlayModel.addBlockElement(offset, false, true, NOTE_PRIORITY, renderer)?.let { inlays.add(it) }
    // The phrasebook gets its own chip right under the note, with its own fold chevron. Folding the
    // note puts the phrasebook away too.
    if (handoff.phrases.isNotEmpty() && !isCollapsed) {
      val pbCollapsed = handoff in phrasebookCollapsed
      val pbGutter = FoldGutterIconRenderer(handoff, FoldTarget.PHRASEBOOK, pbCollapsed)
      val pb = PhrasebookInlayRenderer(project, handoff, pbCollapsed, maxCols, pbGutter)
      editor.inlayModel.addBlockElement(offset, false, true, PHRASEBOOK_PRIORITY, pb)?.let { inlays.add(it) }
    }
  }

  /** Fold/unfold [handoff]'s note (from its gutter chevron). Transient; just re-renders. */
  fun toggleCollapsed(handoff: AvecMoiRingService.Handoff) = onEdt {
    if (!collapsed.remove(handoff)) collapsed.add(handoff)
    refreshAll()
  }

  /** Fold/unfold [handoff]'s phrasebook, independently of its note. Transient; just re-renders. */
  fun togglePhrasebookCollapsed(handoff: AvecMoiRingService.Handoff) = onEdt {
    if (!phrasebookCollapsed.remove(handoff)) phrasebookCollapsed.add(handoff)
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
  }

  private inline fun onEdt(crossinline block: () -> Unit) {
    val app = ApplicationManager.getApplication()
    if (app.isDispatchThread) block() else app.invokeLater { if (!project.isDisposed) block() }
  }

  override fun dispose() {
    inlays.forEach { Disposer.dispose(it) }
    inlays.clear()
  }

  /** Which of a handoff's inlays a fold chevron belongs to. */
  private enum class FoldTarget(val label: String) { NOTE("note"), PHRASEBOOK("phrasebook") }

  /** Gutter chevron that folds/unfolds a note or phrasebook — the platform draws it and routes the click. */
  private inner class FoldGutterIconRenderer(
    private val handoff: AvecMoiRingService.Handoff,
    private val target: FoldTarget,
    private val isCollapsed: Boolean,
  ) : GutterIconRenderer() {
    override fun getIcon(): Icon = if (isCollapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown
    override fun getAlignment(): Alignment = Alignment.LEFT
    override fun getTooltipText(): String =
      (if (isCollapsed) "Expand" else "Collapse") + " avec-moi ${target.label}"
    override fun getClickAction(): AnAction = object : AnAction() {
      override fun actionPerformed(e: AnActionEvent) = when (target) {
        FoldTarget.NOTE -> toggleCollapsed(handoff)
        FoldTarget.PHRASEBOOK -> togglePhrasebookCollapsed(handoff)
      }
    }

    // Identity of the handoff + target + current state: the platform diff-updates gutter icons by equality.
    override fun equals(other: Any?): Boolean =
      other is FoldGutterIconRenderer && other.handoff === handoff && other.target == target &&
        other.isCollapsed == isCollapsed
    override fun hashCode(): Int =
      (System.identityHashCode(handoff) * 31 + target.hashCode()) * 31 + isCollapsed.hashCode()
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
    private val maxColumns: Int,
    private val gutter: GutterIconRenderer,
  ) : EditorCustomElementRenderer {
    private val icon = ICON

    // Puts the fold chevron in the gutter aligned with THIS inlay row (not the code line beneath it).
    override fun calcGutterIconRenderer(inlay: Inlay<*>): GutterIconRenderer = gutter
    private val pad = JBUI.scale(5)
    private val gap = JBUI.scale(4)

    private fun font(editor: Editor) = editor.colorsScheme.getFont(EditorFontType.ITALIC)

    /** What actually gets drawn: one ellipsized line when collapsed, else the soft-wrapped note. */
    private fun displayLines(inlay: Inlay<*>): List<String> =
      if (collapsed) listOf(collapsedLine(inlay)) else wrappedLines(inlay)

    /** The note's first non-blank line, ellipsized to one editor-width line, with "…" if there's more. */
    private fun collapsedLine(inlay: Inlay<*>): String {
      val fm = inlay.editor.contentComponent.getFontMetrics(font(inlay.editor))
      return AvecMoiNoteWrap.collapse(handoff.note, textWidthBudget(inlay.editor)) { fm.stringWidth(it) }
    }

    /**
     * The note split into display lines, soft-wrapped to the editor's visible width so the agent
     * needn't hard-wrap. On a wide monitor this collapses to few lines; on a narrow one it wraps
     * instead of overflowing. (Layout logic lives in AvecMoiNoteWrap so it can be unit-tested.)
     */
    private fun wrappedLines(inlay: Inlay<*>): List<String> {
      val fm = inlay.editor.contentComponent.getFontMetrics(font(inlay.editor))
      return AvecMoiNoteWrap.wrap(handoff.note, textWidthBudget(inlay.editor)) { fm.stringWidth(it) }
    }

    /**
     * Pixels available for note text: the visible editor width minus the icon, padding, and a margin,
     * further capped at [maxColumns] columns when that's set (> 0) so notes don't stretch too wide.
     */
    private fun textWidthBudget(editor: Editor): Int {
      val chrome = pad + icon.iconWidth + gap + pad
      val viewport = (editor.scrollingModel.visibleArea.width - chrome - JBUI.scale(20)).coerceAtLeast(JBUI.scale(200))
      if (maxColumns <= 0) return viewport
      val fm = editor.contentComponent.getFontMetrics(font(editor))
      val capPx = maxColumns * fm.charWidth('m')
      return minOf(viewport, capPx).coerceAtLeast(JBUI.scale(200))
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
      val attrs = noteAttributes(editor, handoff)
      val fg = noteForeground(editor, handoff)
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
      // The avec-moi plugin icon (40x40) scaled down to a marker size for the inlay.
      private val ICON: Icon = IconLoader.getIcon("/META-INF/pluginIcon.svg", NoteInlayRenderer::class.java).let {
        IconUtil.scale(it, null, JBUI.scale(15).toFloat() / it.iconWidth)
      }
    }
  }

  /**
   * A handoff's phrasebook as its own block inlay, directly under the note: a code-styled chip (the
   * editor's own background, so the syntax colors read as they do in the file, outlined in the note's
   * color) with a heading, then one row per phrase — the code in the editor font, highlighted with
   * the file's lexer, then its gloss in the note's italic. Folded, it's the heading alone with a count.
   */
  private class PhrasebookInlayRenderer(
    private val project: Project,
    val handoff: AvecMoiRingService.Handoff,
    private val collapsed: Boolean,
    private val maxColumns: Int,
    private val gutter: GutterIconRenderer,
  ) : EditorCustomElementRenderer {
    private val icon = AllIcons.Actions.IntentionBulb
    private val pad = JBUI.scale(5)
    private val gap = JBUI.scale(4)

    override fun calcGutterIconRenderer(inlay: Inlay<*>): GutterIconRenderer = gutter

    private fun glossFont(editor: Editor) = editor.colorsScheme.getFont(EditorFontType.ITALIC)
    private fun headingFont(editor: Editor) = editor.colorsScheme.getFont(EditorFontType.BOLD_ITALIC)
    private fun codeFont(editor: Editor) = editor.colorsScheme.getFont(EditorFontType.PLAIN)

    private fun heading(): String = if (collapsed) "Phrasebook (${handoff.phrases.size})" else "Phrasebook"

    /** How far phrase rows sit in from the heading (and a wrapped gloss from its code): one separator. */
    private fun indent(editor: Editor): Int =
      editor.contentComponent.getFontMetrics(glossFont(editor)).stringWidth(AvecMoiPhrasebook.GLOSS_SEPARATOR)

    /** Pixels for row text: the visible width less chrome, indent and a margin, capped like the note. */
    private fun budget(editor: Editor): Int {
      val chrome = pad + icon.iconWidth + gap + pad + indent(editor)
      val viewport = (editor.scrollingModel.visibleArea.width - chrome - JBUI.scale(20)).coerceAtLeast(JBUI.scale(200))
      if (maxColumns <= 0) return viewport
      val capPx = maxColumns * editor.contentComponent.getFontMetrics(glossFont(editor)).charWidth('m')
      return minOf(viewport, capPx).coerceAtLeast(JBUI.scale(200))
    }

    private fun rows(editor: Editor): List<AvecMoiPhrasebook.Row> {
      if (collapsed) return emptyList()
      val codeFm = editor.contentComponent.getFontMetrics(codeFont(editor))
      val glossFm = editor.contentComponent.getFontMetrics(glossFont(editor))
      return AvecMoiPhrasebook.layout(handoff.phrases, budget(editor), { codeFm.stringWidth(it) }) { glossFm.stringWidth(it) }
    }

    private fun rowWidth(editor: Editor, row: AvecMoiPhrasebook.Row): Int {
      val codeFm = editor.contentComponent.getFontMetrics(codeFont(editor))
      val glossFm = editor.contentComponent.getFontMetrics(glossFont(editor))
      val gloss = if (row.gloss.isEmpty()) 0 else glossFm.stringWidth(AvecMoiPhrasebook.GLOSS_SEPARATOR + row.gloss)
      return indent(editor) + codeFm.stringWidth(row.code) + gloss
    }

    // Highlighting depends only on the code text and this editor's file/scheme, so lex each phrase once.
    private val highlighted = HashMap<String, List<AvecMoiPhrasebook.Segment>>()

    private fun segments(editor: Editor, code: String): List<AvecMoiPhrasebook.Segment> =
      highlighted.getOrPut(code) {
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        AvecMoiPhrasebook.highlight(code, project, file, editor.colorsScheme)
      }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
      val editor = inlay.editor
      val headingWidth = editor.contentComponent.getFontMetrics(headingFont(editor)).stringWidth(heading())
      val textWidth = maxOf(headingWidth, rows(editor).maxOfOrNull { rowWidth(editor, it) } ?: 0)
      return pad + icon.iconWidth + gap + textWidth + pad
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = inlay.editor.lineHeight * (1 + rows(inlay.editor).size)

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
      val editor = inlay.editor
      val g2 = g as Graphics2D
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      val fg = noteForeground(editor, handoff)
      val lineHeight = editor.lineHeight
      val arc = JBUI.scale(8)
      val width = calcWidthInPixels(inlay)
      // A code-like chip: the editor's own background, outlined in the note's color.
      g2.color = editor.colorsScheme.defaultBackground
      g2.fillRoundRect(targetRegion.x, targetRegion.y, width, targetRegion.height, arc, arc)
      g2.color = Color(fg.red, fg.green, fg.blue, 0x70)
      g2.drawRoundRect(targetRegion.x, targetRegion.y, width - 1, targetRegion.height - 1, arc, arc)
      icon.paintIcon(editor.contentComponent, g2, targetRegion.x + pad, targetRegion.y + (lineHeight - icon.iconHeight) / 2)

      val textX = targetRegion.x + pad + icon.iconWidth + gap
      val ascent = editor.contentComponent.getFontMetrics(glossFont(editor)).ascent
      g2.font = headingFont(editor)
      g2.color = fg
      g2.drawString(heading(), textX, targetRegion.y + ascent)

      val scheme = editor.colorsScheme
      val indent = indent(editor)
      rows(editor).forEachIndexed { i, row ->
        val baseline = targetRegion.y + (i + 1) * lineHeight + ascent
        var x = textX + indent
        if (row.phrase != null) {
          for (seg in AvecMoiPhrasebook.clip(segments(editor, row.phrase.code), row.code)) {
            g2.font = scheme.getFont(EditorFontType.forJavaStyle(seg.attributes?.fontType ?: java.awt.Font.PLAIN))
            // Done handoffs stay uniformly dimmed; otherwise the token's own color, else the editor's text color.
            g2.color = if (handoff.done) fg else seg.attributes?.foregroundColor ?: scheme.defaultForeground
            g2.drawString(seg.text, x, baseline)
            x += g2.fontMetrics.stringWidth(seg.text)
          }
        } else {
          x += indent // a wrapped gloss sits indented under its code
        }
        if (row.gloss.isNotEmpty()) {
          g2.font = glossFont(editor)
          g2.color = fg
          g2.drawString(if (row.phrase == null) row.gloss else AvecMoiPhrasebook.GLOSS_SEPARATOR + row.gloss, x, baseline)
        }
      }
    }
  }

  private companion object {
    // With showAbove, a higher priority sits closer to the code line, so the phrasebook lands under its note.
    const val NOTE_PRIORITY = 0
    const val PHRASEBOOK_PRIORITY = 1

    // Foreground fallbacks for schemes that don't pick up our bundled defaults (light / dark):
    // todo = the ignored / non-project amber, done = the added green.
    val NOTE_FG_FALLBACK = JBColor(Color(0x8C, 0x4F, 0x00), Color(0xD6, 0x9A, 0x6B))
    val NOTE_FG_DONE_FALLBACK = JBColor(Color(0x06, 0x7D, 0x17), Color(0x73, 0xBD, 0x79))
    val NOTE_FG_INFO_FALLBACK = JBColor(Color(0x00, 0x33, 0xB3), Color(0x70, 0xAE, 0xFF))

    /** Amber todo, green done, blue "info" for a read (context) or reference (bookmark). */
    fun noteAttributes(editor: Editor, handoff: AvecMoiRingService.Handoff): TextAttributes? {
      val key = when {
        handoff.done -> AvecMoiColors.NOTE_INLAY_DONE
        handoff.kind == HandoffKind.TODO -> AvecMoiColors.NOTE_INLAY
        else -> AvecMoiColors.NOTE_INLAY_INFO
      }
      return editor.colorsScheme.getAttributes(key)
    }

    fun noteForeground(editor: Editor, handoff: AvecMoiRingService.Handoff): Color =
      noteAttributes(editor, handoff)?.foregroundColor ?: when {
        handoff.done -> NOTE_FG_DONE_FALLBACK
        handoff.kind == HandoffKind.TODO -> NOTE_FG_FALLBACK
        else -> NOTE_FG_INFO_FALLBACK
      }
  }
}
