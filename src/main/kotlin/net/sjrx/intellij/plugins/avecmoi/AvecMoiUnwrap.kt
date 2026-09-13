package net.sjrx.intellij.plugins.avecmoi

/**
 * Undoes the hard line-wrapping some agents (notably Claude Code) bake into terminal output: instead
 * of letting the emulator wrap long lines visually, they insert a real newline at the wrap point and
 * indent the continuation. Copying that text yields spurious `\n  ` breaks mid-content
 * (see anthropics/claude-code#7670).
 *
 * v1 is deliberately the simplest thing that helps: strip every newline-plus-two-spaces sequence,
 * joining the continuation back onto the previous line. That's the shape of a wrapped continuation;
 * genuine blank lines, single-space indents, and unindented newlines are left alone. Kept pure and
 * separate from the action so it's unit-testable.
 */
object AvecMoiUnwrap {

  /** The newline + two-space continuation marker a wrap leaves behind. */
  private const val WRAP_BREAK = "\n  "

  /** [text] with every [WRAP_BREAK] removed (continuations rejoined with no separator). */
  fun unwrap(text: String): String = text.replace(WRAP_BREAK, "")
}
