package net.sjrx.intellij.plugins.avecmoi

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The note-inlay layout logic — pure, so it's plain JUnit (no platform). The width measurer is
 * string length, so `budget` reads as a column count: budget 10 = wrap at 10 characters. This pins
 * the two bugs it actually had (a note ending in a newline rendering blank trailing rows; trailing
 * spaces padding the chip) plus the word-wrap and ellipsis behaviour.
 */
class AvecMoiNoteWrapTest {

  private val byChars: (String) -> Int = { it.length }
  private fun wrap(note: String, budget: Int) = AvecMoiNoteWrap.wrap(note, budget, byChars)
  private fun collapse(note: String, budget: Int) = AvecMoiNoteWrap.collapse(note, budget, byChars)

  // --- wrap ------------------------------------------------------------------------------------

  @Test fun shortLineThatFitsIsNotWrapped() {
    assertEquals(listOf("hello world"), wrap("hello world", 80))
  }

  @Test fun wrapsOnWordBoundariesToFitTheBudget() {
    // "hello world foo" at budget 10: "hello world" is 11 > 10, so "world" starts a new line.
    assertEquals(listOf("hello", "world foo"), wrap("hello world foo", 10))
  }

  @Test fun theNotesOwnNewlinesAreHardBreaks() {
    assertEquals(listOf("alpha", "beta"), wrap("alpha\nbeta", 80))
  }

  @Test fun aBlankLineBetweenParagraphsIsKept() {
    assertEquals(listOf("a", "", "b"), wrap("a\n\nb", 80))
  }

  @Test fun trailingNewlinesDoNotProduceBlankRows() {
    // The bug: a note ending in "\n" used to render empty trailing rows (excess vertical whitespace).
    assertEquals(listOf("done"), wrap("done\n", 80))
    assertEquals(listOf("done"), wrap("done\n\n\n", 80))
  }

  @Test fun trailingSpacesArePadTrimmed() {
    assertEquals(listOf("a", "b"), wrap("a   \nb", 80))
  }

  @Test fun aSingleWordWiderThanTheBudgetOverflowsRatherThanBeingChopped() {
    assertEquals(listOf("supercalifragilistic"), wrap("supercalifragilistic", 5))
  }

  @Test fun anEmptyNoteIsASingleEmptyLine() {
    assertEquals(listOf(""), wrap("", 80))
    assertEquals(listOf(""), wrap("   ", 80))
  }

  // --- collapse --------------------------------------------------------------------------------

  @Test fun collapseOfaSingleShortLineIsThatLineWithNoEllipsis() {
    assertEquals("one line", collapse("one line", 80))
  }

  @Test fun collapseAddsAnEllipsisWhenThereIsMoreThanOneLine() {
    assertEquals("one…", collapse("one\ntwo\nthree", 80))
  }

  @Test fun collapseEllipsizesALongSingleLineToTheBudget() {
    // budget 5 → "s…" must be <= 5 chars → keep 4 chars + "…".
    assertEquals("very…", collapse("verylongsingleline", 5))
  }

  @Test fun collapseOfAnEmptyNoteIsEmpty() {
    assertEquals("", collapse("", 80))
  }
}
