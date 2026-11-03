package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * A handoff's phrasebook, as drawn under its note: layout (pure, unit-testable — like [AvecMoiNoteWrap]
 * it takes width measurers instead of FontMetrics) and syntax highlighting (the file's own lexer and
 * the editor's color scheme, so `?:` and `when` look the way they will in the code).
 */
object AvecMoiPhrasebook {

  /** Separates a phrase's code from its gloss on a shared row. */
  const val GLOSS_SEPARATOR = "  — "

  /**
   * One display row. Normally both [code] and [gloss] are set; when the pair doesn't fit in the budget
   * the gloss wraps onto its own row (empty [code]), drawn indented under the code. [phrase] is the
   * source phrase of a code row (null on a wrapped gloss row), so the renderer can highlight its full
   * code even when [code] was clipped.
   */
  data class Row(val code: String, val gloss: String, val phrase: Phrase? = null)

  /**
   * Lay [phrases] out into rows for a [budget]-pixel width. A phrase whose code and gloss fit together
   * shares one row; otherwise the gloss moves to the next row. Code wider than the budget is clipped
   * with "…" (it's preformatted, so it can't reflow), and so is a gloss that won't fit even alone.
   */
  fun layout(
    phrases: List<Phrase>,
    budget: Int,
    codeWidth: (String) -> Int,
    glossWidth: (String) -> Int,
  ): List<Row> = phrases.flatMap { p ->
    val code = AvecMoiNoteWrap.truncateToWidth(p.code, budget, codeWidth)
    val gloss = p.gloss.trim()
    when {
      gloss.isEmpty() -> listOf(Row(code, "", p))
      codeWidth(code) + glossWidth(GLOSS_SEPARATOR + gloss) <= budget -> listOf(Row(code, gloss, p))
      else -> listOf(
        Row(code, "", p),
        Row("", AvecMoiNoteWrap.truncateToWidth(gloss, budget - glossWidth(GLOSS_SEPARATOR), glossWidth)),
      )
    }
  }

  /** A run of [text] in one highlighting style; null [attributes] = plain (use the note's colors). */
  data class Segment(val text: String, val attributes: TextAttributes?)

  /**
   * Split [code] into highlighted runs using [file]'s syntax highlighter (its lexer + token → color key
   * mapping) resolved against [scheme]. Without a highlighter for the file type, it's one plain run.
   */
  fun highlight(code: String, project: Project?, file: VirtualFile?, scheme: EditorColorsScheme): List<Segment> {
    val highlighter = file?.let { SyntaxHighlighterFactory.getSyntaxHighlighter(it.fileType, project, it) }
      ?: return listOf(Segment(code, null))
    val lexer = highlighter.highlightingLexer
    val out = ArrayList<Segment>()
    lexer.start(code)
    while (lexer.tokenType != null) {
      val type = lexer.tokenType!!
      // Keys run from general to specific, so later ones layer over earlier ones — as the editor does.
      val attrs = highlighter.getTokenHighlights(type).fold(null as TextAttributes?) { acc, key ->
        TextAttributes.merge(acc, scheme.getAttributes(key))
      }
      out.add(Segment(code.substring(lexer.tokenStart, lexer.tokenEnd), attrs))
      lexer.advance()
    }
    return out.ifEmpty { listOf(Segment(code, null)) }
  }

  /**
   * [segments] cut down to [shown] — the possibly-"…"-clipped text of the same code — so a truncated
   * row keeps its colors up to the clip point, then ends in a plain ellipsis.
   */
  fun clip(segments: List<Segment>, shown: String): List<Segment> {
    if (segments.sumOf { it.text.length } == shown.length) return segments
    var budget = shown.removeSuffix("…").length
    val out = ArrayList<Segment>()
    for (s in segments) {
      if (budget <= 0) break
      out.add(if (s.text.length <= budget) s else s.copy(text = s.text.take(budget)))
      budget -= s.text.length
    }
    if (shown.endsWith("…")) out.add(Segment("…", null))
    return out
  }
}
