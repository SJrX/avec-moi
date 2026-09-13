package net.sjrx.intellij.plugins.avecmoi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

  // --- preformatted / code (the indentation + fence bug) ---------------------------------------

  @Test fun anIndentedLineKeepsItsLeadingWhitespace() {
    // Setup: a code block whose middle line is indented two spaces — the reported bug case.
    val note = "if 3 > 4 {\n  return true\n}"

    // Exercise:
    val lines = wrap(note, 80)

    // Verify: the indent survives instead of collapsing to "return true".
    assertEquals(listOf("if 3 > 4 {", "  return true", "}"), lines)
  }

  @Test fun interiorRunsOfSpacesInAnIndentedLineAreNotCollapsed() {
    // Setup: an indented line with a run of interior spaces (prose would collapse these; code mustn't).
    val note = "    a    b"

    // Exercise:
    val lines = wrap(note, 80)

    // Verify: leading and interior spacing both survive verbatim.
    assertEquals(listOf("    a    b"), lines)
  }

  @Test fun anIndentedLineIsTruncatedToBudgetNotWordWrapped() {
    // Setup: an indented line wider than the budget.
    val note = "  abcdefghij"
    val budget = 8

    // Exercise:
    val lines = wrap(note, budget)

    // Verify: kept indent + as many chars as fit + "…", all within budget 8 — not reflowed.
    assertEquals(listOf("  abcde…"), lines)
  }

  @Test fun aFencedBlockIsPreformattedAndTheFenceMarkersAreSwallowed() {
    // Setup: column-0 code fenced in ``` (with and without a language info-string).
    val plainFence = "```\nx := 1\n```"
    val taggedFence = "```go\nx := 1\n```"

    // Exercise:
    val plainLines = wrap(plainFence, 80)
    val taggedLines = wrap(taggedFence, 80)

    // Verify: unindented code inside the fence is preserved; the ``` delimiter lines aren't drawn.
    assertEquals(listOf("x := 1"), plainLines)
    assertEquals(listOf("x := 1"), taggedLines)
  }

  @Test fun columnZeroProseWrapsWhileIndentedCodeIsTruncated() {
    // Setup: identical content at column 0 (prose) and indented (code), both wider than the budget —
    // the guard that the fix didn't over-classify column-0 text as preformatted.
    val prose = "alpha beta gamma"
    val code = "  alpha beta gamma"
    val budget = 10

    // Exercise:
    val proseLines = wrap(prose, budget)
    val codeLines = wrap(code, budget)

    // Verify: prose reflows across lines; indented code stays one line, truncated with an ellipsis.
    assertEquals(listOf("alpha beta", "gamma"), proseLines)
    assertEquals(1, codeLines.size)
    assertTrue("indented code should be truncated, not wrapped: $codeLines", codeLines.single().endsWith("…"))
  }

  @Test fun realAgentHandoffPasteKeepsCodeIndentation() {
    // Setup: the actual paste from the bug report — no fences, code set off by a 2-space indent.
    val note = """
      Add a context key + helpers, mirroring WithScope/scopeFromContext exactly:

        type systemScopeCtxKey struct{}

        func isSystemScope(ctx context.Context) bool { v, _ := ctx.Value(systemScopeCtxKey{}).(bool); return v }

      Step Done when the helpers compile.
    """.trimIndent()

    // Exercise: a wide budget so nothing wraps or truncates — we're isolating indentation.
    val lines = wrap(note, 500)

    // Verify: the indented struct declaration keeps its two-space indent.
    assertTrue("indented code line lost its indent: $lines", lines.contains("  type systemScopeCtxKey struct{}"))
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
