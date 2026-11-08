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

  private val byChars: NoteMeasure = { text, _ -> text.length }
  private fun wrap(note: String, budget: Int) = AvecMoiNoteWrap.layout(note, budget, byChars).map { it.text }
  private fun collapse(note: String, budget: Int) = AvecMoiNoteWrap.collapse(note, budget, byChars).text
  private fun layout(note: String, budget: Int = 80) = AvecMoiNoteWrap.layout(note, budget, byChars)

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

  // --- Markdown ---------------------------------------------------------------------------------

  @Test fun inlineMarkdownIsDrawnStyledAndItsSyntaxIsNotShown() {
    // Exercise SUT
    val line = layout("Call `parse()` **before** the *loop*").single()

    // Verification
    assertEquals(
      listOf(
        NoteSpan("Call "), NoteSpan("parse()", NoteStyle.CODE), NoteSpan(" "), NoteSpan("before", NoteStyle.BOLD),
        NoteSpan(" the "), NoteSpan("loop", NoteStyle.ITALIC),
      ),
      line.spans,
    )
  }

  @Test fun wrappingMeasuresTheRenderedTextNotTheMarkdown() {
    // "aaaa **bbbb**" renders as 9 columns, so it fits a budget of 9 even though the source is 13.
    assertEquals(listOf("aaaa bbbb"), wrap("aaaa **bbbb**", 9))
  }

  @Test fun aSpaceInsideACodeSpanStaysCodeWhenTheLineIsJoined() {
    assertEquals(listOf(NoteSpan("a b", NoteStyle.CODE)), layout("`a b`").single().spans)
  }

  @Test fun aBulletItemGetsABulletAndItsWrappedRowsHangUnderItsText() {
    // Exercise SUT — "• " is 2 columns, so the item's text wraps at 10 - 2 = 8
    val rows = layout("- alpha beta gamma", 10)

    // Verification
    assertEquals(listOf("• alpha", "beta", "gamma"), rows.map { it.text })
    assertEquals(listOf(0, 2, 2), rows.map { it.indent })
  }

  @Test fun aNumberedItemKeepsItsNumber() {
    assertEquals(listOf("1. first", "2. second"), wrap("1. first\n2. second", 80))
  }

  @Test fun anIndentedBulletIsANestedListItemNotCode() {
    // Exercise SUT
    val rows = layout("- outer\n  - inner")

    // Verification — nested by its indent, drawn as prose rather than verbatim code
    assertEquals(listOf("• outer", "• inner"), rows.map { it.text })
    assertEquals(2, rows[1].indent)
    assertEquals(NoteStyle.TEXT, rows[1].spans.single().style)
  }

  @Test fun anIndentedCodeLineIsDrawnAsCode() {
    assertEquals(listOf(NoteSpan("  return x", NoteStyle.CODE)), layout("  return x").single().spans)
  }

  @Test fun markdownInsideAFenceIsLeftAsWritten() {
    assertEquals(listOf(NoteSpan("**not bold**", NoteStyle.CODE)), layout("```\n**not bold**\n```").single().spans)
  }

  @Test fun aHeadingIsABoldLineWithoutTheHashes() {
    assertEquals(listOf(NoteSpan("Next up", NoteStyle.BOLD)), layout("## Next up").single().spans)
  }

  @Test fun collapseRendersTheMarkdownOfTheFirstLine() {
    assertEquals("• use parse()…", collapse("- use `parse()`\n- then test", 80))
  }

  @Test fun collapseSkipsAnOpeningFence() {
    assertEquals("x := 1", collapse("```go\nx := 1\n```", 80))
  }
}
