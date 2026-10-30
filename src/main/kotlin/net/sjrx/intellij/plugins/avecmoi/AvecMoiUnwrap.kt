package net.sjrx.intellij.plugins.avecmoi

/**
 * Undoes the hard line-wrapping some agents (notably Claude Code) bake into terminal output: instead
 * of letting the emulator wrap long lines visually, they insert a real newline at the wrap point and
 * indent the continuation. Copying that text yields spurious breaks mid-content
 * (see anthropics/claude-code#7670) — a URL split across lines, a paragraph chopped up.
 *
 * A wrap break is a newline whose continuation is indented (any number of spaces — prose wraps with a
 * two-space hang, code/output blocks with more). We also swallow any spaces immediately before the
 * newline so the join is clean from both sides. What replaces the break is the caller's choice:
 *
 *  - [join] with `""` — rejoin directly, no separator. Best for URLs / paths, where the wrap fell
 *    mid-token and any inserted space would corrupt it.
 *  - [join] with `" "` — rejoin with a single space. Best for prose, where the wrap fell on a word
 *    boundary and the eaten space needs restoring.
 *
 * Genuine line breaks (a newline at column 0, blank lines between paragraphs) have no indented
 * continuation, so they're left untouched. Kept pure and separate from the actions so it's testable.
 *
 * Indentation alone can't tell every break apart: Claude Code indents EVERY line of a code block, so
 * a real newline between two commands looks exactly like a wrap break. When the terminal width is
 * known we also apply the word-wrap test — a break is only a wrap if the next word wouldn't have fit
 * on the line — so a newline after a short line survives. Measured in chars, not display cells, and
 * against the CURRENT width (a resize since the output was printed can misjudge). A selection starting
 * mid-line undercounts its first line, which errs towards keeping that break.
 */
object AvecMoiUnwrap {

  /** A hard wrap break: any spaces, a newline, then the continuation's indentation (one+ spaces). */
  private val WRAP_BREAK = Regex(" *\n +")

  /**
   * Slack, in columns, for an agent that wraps a little short of the terminal edge (a right margin),
   * so a break whose next word would have *just* fitted still counts as a wrap.
   */
  private const val WRAP_SLACK = 2

  /**
   * [text] with every wrap break replaced by [separator] (typically `""` or `" "`). With [columns]
   * (the terminal width), a break after a line the next word would have fitted on is kept as-is.
   */
  fun unwrap(text: String, separator: String, columns: Int? = null): String =
    WRAP_BREAK.replace(text) { m ->
      if (columns == null || wouldNotHaveFit(text, m, columns)) separator else m.value
    }

  /** The word-wrap test: the line before [m] plus a space plus the next word overflows [columns]. */
  private fun wouldNotHaveFit(text: String, m: MatchResult, columns: Int): Boolean {
    val newline = text.indexOf('\n', m.range.first)
    val lineLength = newline - (text.lastIndexOf('\n', newline - 1) + 1)
    val nextWord = text.substring(m.range.last + 1).takeWhile { !it.isWhitespace() }
    return lineLength + 1 + nextWord.length > columns - WRAP_SLACK
  }
}
