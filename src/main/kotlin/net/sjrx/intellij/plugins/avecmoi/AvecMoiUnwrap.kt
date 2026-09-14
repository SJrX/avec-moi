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
 */
object AvecMoiUnwrap {

  /** A hard wrap break: any spaces, a newline, then the continuation's indentation (one+ spaces). */
  private val WRAP_BREAK = Regex(" *\n +")

  /** [text] with every wrap break replaced by [separator] (typically `""` or `" "`). */
  fun unwrap(text: String, separator: String): String = WRAP_BREAK.replace(text, separator)
}
