package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.SimpleTextAttributes

/** One styled piece of a note's tool-window row: [text] in a [SimpleTextAttributes] font [style]. */
data class NoteTreeFragment(val text: String, val style: Int)

/**
 * The note's one-row summary in the tool window tree: its first line with the Markdown subset applied
 * (not shown as syntax), cut to [budget] pixels with "…" when it overflows or the note goes on. The
 * full note is in the wrapped pane under the tree, so the row only has to say what the note is about.
 * Pure (takes a [NoteMeasure]), so it's plain-JUnit testable.
 */
object AvecMoiNoteTreeRow {

  const val NO_NOTE = "(no note)"

  fun fragments(note: String, budget: Int, widthOf: NoteMeasure, textStyle: NoteTextStyle): List<NoteTreeFragment> {
    val line = AvecMoiNoteWrap.collapse(note, budget, widthOf)
    if (line.spans.isEmpty()) return listOf(NoteTreeFragment(NO_NOTE, SimpleTextAttributes.STYLE_ITALIC))
    return line.spans.map { NoteTreeFragment(it.text, fontStyle(textStyle, it.style)) }
  }

  /** The tree font style for [style], following the same "Draw note text" setting as the inlays. */
  fun fontStyle(textStyle: NoteTextStyle, style: NoteStyle): Int = when (textStyle.fontType(style)) {
    EditorFontType.BOLD -> SimpleTextAttributes.STYLE_BOLD
    EditorFontType.ITALIC -> SimpleTextAttributes.STYLE_ITALIC
    EditorFontType.BOLD_ITALIC -> SimpleTextAttributes.STYLE_BOLD or SimpleTextAttributes.STYLE_ITALIC
    else -> SimpleTextAttributes.STYLE_PLAIN
  }
}
