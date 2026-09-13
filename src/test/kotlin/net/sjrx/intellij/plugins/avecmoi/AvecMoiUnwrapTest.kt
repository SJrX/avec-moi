package net.sjrx.intellij.plugins.avecmoi

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The clipboard-unwrap transform — pure, so plain JUnit (no platform). Pins v1: a newline followed by
 * exactly two spaces (an agent's wrapped-line continuation) is removed, and nothing else is touched.
 */
class AvecMoiUnwrapTest {

  private fun unwrap(text: String) = AvecMoiUnwrap.unwrap(text)

  @Test fun aNewlinePlusTwoSpacesIsRemovedAndTheLinesRejoin() {
    // Setup: a line hard-wrapped by the agent, its continuation indented two spaces.
    val copied = "the quick brown\n  fox jumps"

    // Exercise:
    val result = unwrap(copied)

    // Verify: the break is gone and the continuation joins the previous line (no separator).
    assertEquals("the quick brownfox jumps", result)
  }

  @Test fun everyWrapBreakInTheTextIsRemoved() {
    // Setup: several wrap breaks across one long logical line.
    val copied = "alpha\n  beta\n  gamma\n  delta"

    // Exercise:
    val result = unwrap(copied)

    // Verify: all of them collapse.
    assertEquals("alphabetagammadelta", result)
  }

  @Test fun aPlainNewlineWithNoIndentIsLeftAlone() {
    // Setup: a genuine line break (not a wrap continuation) — no leading spaces.
    val copied = "first line\nsecond line"

    // Exercise:
    val result = unwrap(copied)

    // Verify: untouched.
    assertEquals("first line\nsecond line", result)
  }

  @Test fun aBlankLineBetweenParagraphsIsPreserved() {
    // Setup: two paragraphs separated by a blank line.
    val copied = "para one\n\npara two"

    // Exercise:
    val result = unwrap(copied)

    // Verify: the blank line survives — it's "\n\n", not "\n  ".
    assertEquals("para one\n\npara two", result)
  }

  @Test fun onlyTheFirstTwoSpacesAfterANewlineAreConsumed() {
    // Setup: a deeper (four-space) indent — v1 targets exactly newline + two spaces.
    val copied = "head\n    tail"

    // Exercise:
    val result = unwrap(copied)

    // Verify: the "\n  " prefix goes, leaving the remaining two spaces of indentation.
    assertEquals("head  tail", result)
  }

  @Test fun aSingleSpaceIndentIsNotAWrapBreak() {
    // Setup: newline + one space — below the two-space threshold.
    val copied = "head\n tail"

    // Exercise:
    val result = unwrap(copied)

    // Verify: left alone.
    assertEquals("head\n tail", result)
  }

  @Test fun textWithNoWrapBreaksIsReturnedUnchanged() {
    // Setup: an ordinary single line.
    val copied = "nothing to unwrap here"

    // Exercise:
    val result = unwrap(copied)

    // Verify:
    assertEquals("nothing to unwrap here", result)
  }

  @Test fun anEmptyStringIsEmpty() {
    // Setup / Exercise / Verify:
    assertEquals("", unwrap(""))
  }
}
