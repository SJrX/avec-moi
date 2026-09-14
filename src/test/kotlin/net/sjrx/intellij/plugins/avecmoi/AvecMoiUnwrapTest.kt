package net.sjrx.intellij.plugins.avecmoi

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The clipboard-unwrap transform — pure, so plain JUnit (no platform). A wrap break is (any spaces +)
 * a newline + an indented continuation, of any indent width. It's replaced by the caller's separator:
 * "" to join directly (URLs/paths), " " to reflow prose. Genuine breaks are left alone.
 */
class AvecMoiUnwrapTest {

  private fun join(text: String) = AvecMoiUnwrap.unwrap(text, "")
  private fun space(text: String) = AvecMoiUnwrap.unwrap(text, " ")

  @Test fun joinRejoinsABrokenLineWithNoSeparator() {
    // Setup: a URL split across two lines, the continuation indented (as Claude Code emits it).
    val copied = "https://example.com/x?q=aaaa\n  aaaa"

    // Exercise:
    val result = join(copied)

    // Verify: one contiguous URL, no gap.
    assertEquals("https://example.com/x?q=aaaaaaaa", result)
  }

  @Test fun joinCollapsesDeepIndentSoUrlsHaveNoLeftoverGap() {
    // Setup: the real bug — a code/output block wraps with a FIVE-space hang, not two.
    val copied = "aaaaaaaa\n     aaaaaaaa"

    // Exercise:
    val result = join(copied)

    // Verify: the whole indent is consumed — no stray spaces survive mid-token.
    assertEquals("aaaaaaaaaaaaaaaa", result)
  }

  @Test fun spaceRejoinsProseWithASingleSpace() {
    // Setup: prose wrapped at a word boundary — the space was eaten by the break.
    val copied = "log-injection or terminal-escape\n  payload"

    // Exercise:
    val result = space(copied)

    // Verify: the words are separated again.
    assertEquals("log-injection or terminal-escape payload", result)
  }

  @Test fun aTrailingSpaceBeforeTheBreakIsNormalizedAway() {
    // Setup: some wraps leave a space before the newline too.
    val copied = "terminal-escape \n  payload"

    // Exercise / Verify: join eats both sides (no double); space yields exactly one.
    assertEquals("terminal-escapepayload", join(copied))
    assertEquals("terminal-escape payload", space(copied))
  }

  @Test fun everyWrapBreakInTheTextIsHandled() {
    // Setup: several breaks, mixed indent widths.
    val copied = "alpha\n  beta\n    gamma\n  delta"

    // Exercise / Verify:
    assertEquals("alphabetagammadelta", join(copied))
    assertEquals("alpha beta gamma delta", space(copied))
  }

  @Test fun aPlainNewlineWithNoIndentIsLeftAlone() {
    // Setup: a genuine line break — no indented continuation.
    val copied = "first line\nsecond line"

    // Exercise / Verify: both variants leave it untouched.
    assertEquals("first line\nsecond line", join(copied))
    assertEquals("first line\nsecond line", space(copied))
  }

  @Test fun aBlankLineBetweenParagraphsIsPreserved() {
    // Setup: two paragraphs separated by a blank line (no indent after the first newline).
    val copied = "para one\n\npara two"

    // Exercise / Verify:
    assertEquals("para one\n\npara two", join(copied))
    assertEquals("para one\n\npara two", space(copied))
  }

  @Test fun textWithNoWrapBreaksIsReturnedUnchanged() {
    // Setup / Exercise / Verify:
    assertEquals("nothing to unwrap here", join("nothing to unwrap here"))
    assertEquals("nothing to unwrap here", space("nothing to unwrap here"))
  }

  @Test fun anEmptyStringIsEmpty() {
    // Setup / Exercise / Verify:
    assertEquals("", join(""))
    assertEquals("", space(""))
  }
}
