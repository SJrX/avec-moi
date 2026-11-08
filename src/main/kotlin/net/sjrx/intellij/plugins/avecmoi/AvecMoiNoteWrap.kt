package net.sjrx.intellij.plugins.avecmoi

/** Pixel width of [text] drawn in [style]. Tests pass a char count; the renderer uses FontMetrics. */
typealias NoteMeasure = (text: String, style: NoteStyle) -> Int

/** One display row of a note: styled spans drawn left to right, starting [indent] pixels in. */
data class NoteLine(val spans: List<NoteSpan>, val indent: Int = 0) {
  /** The row's text without styling — what tests compare against. */
  val text: String get() = spans.joinToString("") { it.text }

  fun width(widthOf: NoteMeasure): Int = indent + spans.sumOf { widthOf(it.text, it.style) }
}

/**
 * The note-inlay text layout: soft-wrapping and single-line collapse, with the Markdown subset from
 * [AvecMoiNoteMarkdown]. Pulled out of the renderer so it's pure and unit-testable — it takes a
 * [NoteMeasure] and a [budget] (pixels available) instead of an editor/FontMetrics, so tests can pass
 * a simple char-count width.
 */
object AvecMoiNoteWrap {

  /** A list item: leading indent, a bullet (`-`, `*`, `+`) or number (`1.`, `1)`), then its text. */
  private val LIST_ITEM = Regex("""^([ \t]*)([-*+]|\d{1,3}[.)])[ \t]+(.*)$""")

  /** A `#`..`######` heading; rendered as a bold line, not a bigger font. */
  private val HEADING = Regex("""^#{1,6}[ \t]+(.*?)[ \t#]*$""")

  /**
   * The note as display rows. The note's own newlines are hard breaks (paragraphs/lists) and are kept,
   * blank lines included. Per logical line:
   *  - inside a ``` fence, or indented (and not a list item): code, drawn verbatim (indent and interior
   *    spacing kept) and truncated to [budget] rather than reflowed. Fence lines aren't drawn.
   *  - a list item: a bullet (or its number), then the item's text wrapped with a hanging indent.
   *  - a `#` heading: its text, bold.
   *  - anything else: prose, inline Markdown parsed, greedily word-wrapped to [budget].
   * Leading/trailing blank lines and per-line trailing whitespace are trimmed so a note that ends in
   * a newline doesn't render empty rows. A single word wider than the budget overflows rather than
   * being chopped.
   */
  fun layout(note: String, budget: Int, widthOf: NoteMeasure): List<NoteLine> {
    val out = ArrayList<NoteLine>()
    var inFence = false
    for (raw in trimBlankEdges(note.split("\n"))) {
      if (isFenceMarker(raw)) {
        inFence = !inFence
        continue // the ``` line itself is a delimiter, not content — don't render it.
      }
      val logical = raw.trimEnd()
      val item = if (inFence) null else LIST_ITEM.matchEntire(logical)
      when {
        item != null -> out.addAll(listItem(item, budget, widthOf))
        isPreformatted(logical, inFence) -> out.add(codeLine(logical, budget, widthOf))
        logical.isBlank() -> out.add(NoteLine(emptyList()))
        else -> out.addAll(wrapSpans(proseSpans(logical), budget, widthOf, 0))
      }
    }
    return out.ifEmpty { listOf(NoteLine(emptyList())) }
  }

  /**
   * The note's first non-blank line as one [budget]-wide row, Markdown rendered (not shown as syntax),
   * ellipsized with "…" if it doesn't fit or there's more to the note.
   */
  fun collapse(note: String, budget: Int, widthOf: NoteMeasure): NoteLine {
    val lines = note.split("\n")
    val firstIndex = lines.indexOfFirst { it.isNotBlank() && !isFenceMarker(it) }
    if (firstIndex < 0) return NoteLine(emptyList())
    val raw = lines[firstIndex].trimEnd()
    val inFence = lines.take(firstIndex).count { isFenceMarker(it) } % 2 == 1
    val item = if (inFence) null else LIST_ITEM.matchEntire(raw)
    val spans = when {
      item != null -> listOf(NoteSpan(marker(item))) + AvecMoiNoteMarkdown.parseInline(item.groupValues[3])
      isPreformatted(raw, inFence) -> listOf(NoteSpan(raw.trim(), NoteStyle.CODE))
      else -> proseSpans(raw.trim())
    }
    val hasMore = lines.withIndex().any { (i, l) -> i != firstIndex && l.isNotBlank() && !isFenceMarker(l) }
    return NoteLine(ellipsize(spans, budget, widthOf, force = hasMore))
  }

  /** Prose spans for an unindented line: a heading is emboldened, anything else parsed inline. */
  private fun proseSpans(logical: String): List<NoteSpan> {
    val heading = HEADING.matchEntire(logical)
    return if (heading != null) {
      AvecMoiNoteMarkdown.emboldened(AvecMoiNoteMarkdown.parseInline(heading.groupValues[1]))
    } else {
      AvecMoiNoteMarkdown.parseInline(logical)
    }
  }

  /** "• " for a bullet, or the item's own number ("2. ") for a numbered list. */
  private fun marker(item: MatchResult): String {
    val m = item.groupValues[2]
    return if (m.first().isDigit()) "$m " else "• "
  }

  /** A list item: its first row starts with the marker; wrapped rows hang under the item's text. */
  private fun listItem(item: MatchResult, budget: Int, widthOf: NoteMeasure): List<NoteLine> {
    val lead = widthOf(item.groupValues[1].replace("\t", "    "), NoteStyle.TEXT)
    val marker = marker(item)
    val hang = lead + widthOf(marker, NoteStyle.TEXT)
    val rows = wrapSpans(AvecMoiNoteMarkdown.parseInline(item.groupValues[3]), budget - hang, widthOf, hang)
    val first = rows.first()
    return listOf(NoteLine(AvecMoiNoteMarkdown.merge(listOf(NoteSpan(marker)) + first.spans), lead)) + rows.drop(1)
  }

  /** A verbatim code row (no Markdown parsing), truncated to [budget]. */
  private fun codeLine(logical: String, budget: Int, widthOf: NoteMeasure): NoteLine {
    if (logical.isBlank()) return NoteLine(emptyList())
    return NoteLine(listOf(NoteSpan(truncateToWidth(logical, budget) { widthOf(it, NoteStyle.CODE) }, NoteStyle.CODE)))
  }

  /** A word: its styled pieces, plus the style of the space before it (so a space inside `a b` stays code). */
  private class Word(val pieces: List<NoteSpan>, val spaceStyle: NoteStyle)

  /** Splits [spans] into words at spaces, collapsing runs of spaces. */
  private fun words(spans: List<NoteSpan>): List<Word> {
    val out = ArrayList<Word>()
    var pieces = ArrayList<NoteSpan>()
    var spaceStyle = NoteStyle.TEXT
    fun endWord() {
      if (pieces.isNotEmpty()) out.add(Word(pieces, spaceStyle))
      pieces = ArrayList()
    }
    for (span in spans) {
      span.text.split(' ').forEachIndexed { k, part ->
        if (k > 0) {
          endWord()
          spaceStyle = span.style
        }
        if (part.isNotEmpty()) pieces.add(NoteSpan(part, span.style))
      }
    }
    endWord()
    return out
  }

  /** Greedy word-wrap of [spans] to [budget]; every row gets [indent]. */
  private fun wrapSpans(spans: List<NoteSpan>, budget: Int, widthOf: NoteMeasure, indent: Int): List<NoteLine> {
    val rows = ArrayList<NoteLine>()
    var row = ArrayList<NoteSpan>()
    var rowWidth = 0
    for (word in words(spans)) {
      val w = word.pieces.sumOf { widthOf(it.text, it.style) }
      if (row.isEmpty()) {
        row.addAll(word.pieces)
        rowWidth = w
        continue
      }
      val space = widthOf(" ", word.spaceStyle)
      if (rowWidth + space + w <= budget) {
        row.add(NoteSpan(" ", word.spaceStyle))
        row.addAll(word.pieces)
        rowWidth += space + w
      } else {
        rows.add(NoteLine(AvecMoiNoteMarkdown.merge(row), indent))
        row = ArrayList(word.pieces)
        rowWidth = w
      }
    }
    rows.add(NoteLine(AvecMoiNoteMarkdown.merge(row), indent))
    return rows
  }

  /** [spans] cut to fit [budget] with a trailing "…" — always when [force], else only if they overflow. */
  private fun ellipsize(spans: List<NoteSpan>, budget: Int, widthOf: NoteMeasure, force: Boolean): List<NoteSpan> {
    fun width(s: List<NoteSpan>) = s.sumOf { widthOf(it.text, it.style) }
    if (!force && width(spans) <= budget) return spans
    val kept = spans.toMutableList()
    val ellipsis = NoteSpan("…")
    while (kept.isNotEmpty() && width(kept) + widthOf(ellipsis.text, ellipsis.style) > budget) {
      val last = kept.last()
      if (last.text.length <= 1) kept.removeAt(kept.size - 1) else kept[kept.size - 1] = last.copy(text = last.text.dropLast(1))
    }
    return AvecMoiNoteMarkdown.merge(kept + ellipsis)
  }

  /** Drops leading and trailing blank lines so a note that begins/ends in newlines renders no empty rows. */
  private fun trimBlankEdges(lines: List<String>): List<String> =
    lines.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }

  /** True if [raw] is a code-fence delimiter (a line whose first non-space content is ```, info-string allowed). */
  private fun isFenceMarker(raw: String): Boolean =
    raw.trimStart().startsWith("```")

  /**
   * True if [logical] should be rendered verbatim as code rather than word-wrapped as prose: either
   * we're currently inside a ``` fence, or the line is indented (agents set code off from prose by
   * indenting it). Indented list items are checked first and stay list items.
   */
  private fun isPreformatted(logical: String, inFence: Boolean): Boolean =
    inFence || logical.startsWith(" ") || logical.startsWith("\t")

  /**
   * A preformatted line clipped to [budget] pixels, keeping its leading whitespace. If it already
   * fits, it's returned unchanged; otherwise trailing characters are dropped and a "…" appended.
   */
  internal fun truncateToWidth(logical: String, budget: Int, widthOf: (String) -> Int): String {
    if (widthOf(logical) <= budget) return logical
    var s = logical
    while (s.isNotEmpty() && widthOf("$s…") > budget) s = s.dropLast(1)
    return "$s…"
  }
}
