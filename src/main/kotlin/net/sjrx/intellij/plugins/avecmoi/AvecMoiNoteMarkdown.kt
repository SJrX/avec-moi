package net.sjrx.intellij.plugins.avecmoi

/** How a run of note text is drawn. The renderer maps each to a font (and a background for code). */
enum class NoteStyle { TEXT, BOLD, ITALIC, BOLD_ITALIC, CODE }

/** A run of note text in one [style]. */
data class NoteSpan(val text: String, val style: NoteStyle = NoteStyle.TEXT)

/**
 * The inline Markdown subset handoff notes support: `code`, **bold** / __bold__, *italic* / _italic_,
 * ***both***, and backslash escapes. Pure, so it's plain-JUnit testable. Deliberately forgiving: any
 * marker that doesn't pair up is left as literal text rather than guessed at, so a stray `*` in prose
 * (or `snake_case_name`) reads exactly as written.
 */
object AvecMoiNoteMarkdown {

  private const val ESCAPABLE = "\\`*_[]#"

  /** [text] (one line, no newlines) as styled spans, adjacent same-style spans merged. */
  fun parseInline(text: String): List<NoteSpan> {
    val out = ArrayList<NoteSpan>()
    parse(text, bold = false, italic = false, out)
    return merge(out)
  }

  /** [spans] with every run made bold too (code stays code) — for `#` headings. */
  fun emboldened(spans: List<NoteSpan>): List<NoteSpan> = merge(
    spans.map {
      when (it.style) {
        NoteStyle.TEXT -> it.copy(style = NoteStyle.BOLD)
        NoteStyle.ITALIC -> it.copy(style = NoteStyle.BOLD_ITALIC)
        else -> it
      }
    },
  )

  /** Joins adjacent spans of the same style and drops empty ones. */
  fun merge(spans: List<NoteSpan>): List<NoteSpan> {
    val out = ArrayList<NoteSpan>()
    for (s in spans) {
      if (s.text.isEmpty()) continue
      val last = out.lastOrNull()
      if (last != null && last.style == s.style) out[out.size - 1] = last.copy(text = last.text + s.text) else out.add(s)
    }
    return out
  }

  private fun styleOf(bold: Boolean, italic: Boolean): NoteStyle = when {
    bold && italic -> NoteStyle.BOLD_ITALIC
    bold -> NoteStyle.BOLD
    italic -> NoteStyle.ITALIC
    else -> NoteStyle.TEXT
  }

  private fun parse(s: String, bold: Boolean, italic: Boolean, out: MutableList<NoteSpan>) {
    val buf = StringBuilder()
    fun flush() {
      if (buf.isNotEmpty()) out.add(NoteSpan(buf.toString(), styleOf(bold, italic)))
      buf.setLength(0)
    }
    var i = 0
    while (i < s.length) {
      val c = s[i]
      if (c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE) {
        buf.append(s[i + 1])
        i += 2
        continue
      }
      if (c == '`') {
        val run = runLength(s, i)
        val close = findBacktickClose(s, i + run, run)
        if (close < 0) {
          buf.append(s, i, i + run)
          i += run
          continue
        }
        flush()
        var code = s.substring(i + run, close)
        // CommonMark: one space of padding each side is stripped, so `` `x` `` can hold a backtick.
        if (code.length >= 2 && code.startsWith(" ") && code.endsWith(" ") && code.isNotBlank()) {
          code = code.substring(1, code.length - 1)
        }
        out.add(NoteSpan(code, NoteStyle.CODE))
        i = close + run
        continue
      }
      if (c == '*' || c == '_') {
        val run = runLength(s, i)
        val open = if (run <= 3) findOpening(s, i, run, c) else null
        if (open == null) {
          buf.append(s, i, i + run)
          i += run
          continue
        }
        // Any opener chars left over (e.g. the first * of ***a** ) stay literal.
        buf.append(s, i, open.start)
        flush()
        parse(s.substring(open.start + open.n, open.close), bold || open.n >= 2, italic || open.n != 2, out)
        i = open.close + open.n
        continue
      }
      buf.append(c)
      i++
    }
    flush()
  }

  /** How many times the char at [from] repeats from there. */
  private fun runLength(s: String, from: Int): Int {
    var j = from
    while (j < s.length && s[j] == s[from]) j++
    return j - from
  }

  /** The start of the next backtick run of exactly [run] length at or after [from], or -1. */
  private fun findBacktickClose(s: String, from: Int, run: Int): Int {
    var j = from
    while (j < s.length) {
      if (s[j] == '`') {
        val r = runLength(s, j)
        if (r == run) return j
        j += r
      } else {
        j++
      }
    }
    return -1
  }

  /** An emphasis run opens only if text follows it; `_` also not mid-word (so snake_case stays literal). */
  private fun canOpen(s: String, at: Int, run: Int): Boolean {
    val next = s.getOrNull(at + run) ?: return false
    if (next.isWhitespace()) return false
    if (s[at] == '_' && s.getOrNull(at - 1)?.isLetterOrDigit() == true) return false
    return true
  }

  /** An emphasis that pairs up: [n] marker chars opening at [start], closed by [n] chars at [close]. */
  private class Opening(val start: Int, val n: Int, val close: Int)

  /**
   * How the [run] of [c]s at [at] opens emphasis, if it does. The whole run is tried first, then [n]
   * of its chars. Those are the LEADING n when the rest of the run closes inside the span (***a* b**:
   * bold outside, italic inside); otherwise the trailing n, leaving the rest literal (***a**: a
   * stray * then bold "a"). Pure: nothing is consumed here.
   */
  private fun findOpening(s: String, at: Int, run: Int, c: Char): Opening? {
    if (canOpen(s, at, run)) findEmphasisClose(s, at + run, c, run)?.let { return Opening(at, run, it) }
    for (n in run - 1 downTo 1) {
      val outer = findEmphasisClose(s, at + run, c, n)
      val inner = findEmphasisClose(s, at + run, c, run - n)
      if (outer != null && inner != null && inner < outer) return Opening(at, n, outer)
      val start = at + run - n
      if (!canOpen(s, start, n)) continue
      findEmphasisClose(s, start + n, c, n)?.let { return Opening(start, n, it) }
    }
    return null
  }

  /**
   * Where [n] closing [c]s start after [from]. A run of exactly [n] is preferred (so *a **b** c* nests);
   * failing that, the last [n] chars of a longer run close it (so **a *b*** closes both at once) — but
   * only when its other chars close an opener inside the span, so the ** in *x **y** still pairs
   * with its own ** and the lone * stays literal. A closer follows non-space, and for `_` isn't
   * followed by a letter/digit.
   */
  private fun findEmphasisClose(s: String, from: Int, c: Char, n: Int): Int? =
    closingRuns(s, from, c).firstOrNull { (_, r) -> r == n }?.first
      ?: closingRuns(s, from, c)
        .firstOrNull { (j, r) -> r > n && hasOpener(s, from, j, c, r - n) }
        ?.let { (j, r) -> j + r - n }

  /** True if [s] between [from] and [until] has a run of exactly [n] [c]s that can open emphasis. */
  private fun hasOpener(s: String, from: Int, until: Int, c: Char, n: Int): Boolean {
    var j = from
    while (j < until) {
      if (s[j] != c) {
        j++
        continue
      }
      val r = runLength(s, j)
      if (r == n && canOpen(s, j, r)) return true
      j += r
    }
    return false
  }

  /** Each run of [c]s after [from] that could close emphasis, as (start, length); code spans skipped. */
  private fun closingRuns(s: String, from: Int, c: Char): Sequence<Pair<Int, Int>> = sequence {
    var j = from
    while (j < s.length) {
      if (s[j] == '`') {
        // Skip a code span inside the emphasis so a marker inside it can't close the emphasis.
        val r = runLength(s, j)
        val close = findBacktickClose(s, j + r, r)
        j = if (close < 0) j + r else close + r
        continue
      }
      if (s[j] == '\\' && s.getOrNull(j + 1)?.let { it in ESCAPABLE } == true) {
        j += 2 // an escaped marker is literal, so it can't close
        continue
      }
      if (s[j] != c) {
        j++
        continue
      }
      val r = runLength(s, j)
      val closes = j > from && !s[j - 1].isWhitespace() &&
        !(c == '_' && s.getOrNull(j + r)?.isLetterOrDigit() == true)
      if (closes) yield(j to r)
      j += r
    }
  }
}
