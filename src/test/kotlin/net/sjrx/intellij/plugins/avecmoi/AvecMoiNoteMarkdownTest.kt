package net.sjrx.intellij.plugins.avecmoi

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The inline Markdown subset in handoff notes — pure, so plain JUnit. The cases that matter most are
 * the ones that must NOT be styled: agents' prose is full of stray `*`, `_` and snake_case names, and
 * guessing wrong there garbles the note.
 */
class AvecMoiNoteMarkdownTest {

  private fun parse(text: String) = AvecMoiNoteMarkdown.parseInline(text)
  private fun text(t: String) = NoteSpan(t)
  private fun code(t: String) = NoteSpan(t, NoteStyle.CODE)
  private fun bold(t: String) = NoteSpan(t, NoteStyle.BOLD)
  private fun italic(t: String) = NoteSpan(t, NoteStyle.ITALIC)

  @Test fun plainTextIsOneTextSpan() {
    assertEquals(listOf(text("just words")), parse("just words"))
  }

  @Test fun backticksMakeCode() {
    assertEquals(listOf(text("call "), code("foo()"), text(" here")), parse("call `foo()` here"))
  }

  @Test fun markersInsideCodeAreLiteral() {
    assertEquals(listOf(code("a*b*c")), parse("`a*b*c`"))
  }

  @Test fun doubleBackticksCanHoldABacktick() {
    assertEquals(listOf(code("a`b")), parse("`` a`b ``"))
  }

  @Test fun doubleStarsAndUnderscoresMakeBold() {
    assertEquals(listOf(bold("a"), text(" "), bold("b")), parse("**a** __b__"))
  }

  @Test fun singleStarsAndUnderscoresMakeItalic() {
    assertEquals(listOf(italic("a"), text(" "), italic("b")), parse("*a* _b_"))
  }

  @Test fun tripleStarsMakeBoldItalic() {
    assertEquals(listOf(NoteSpan("both", NoteStyle.BOLD_ITALIC)), parse("***both***"))
  }

  @Test fun boldNestsInsideItalic() {
    assertEquals(
      listOf(italic("a "), NoteSpan("b", NoteStyle.BOLD_ITALIC), italic(" c")),
      parse("*a **b** c*"),
    )
  }

  @Test fun codeNestsInsideBold() {
    assertEquals(listOf(bold("use "), code("x"), bold(" now")), parse("**use `x` now**"))
  }

  @Test fun snakeCaseIsNotItalic() {
    assertEquals(listOf(text("snake_case_name")), parse("snake_case_name"))
  }

  @Test fun aStarSurroundedBySpacesIsLiteral() {
    assertEquals(listOf(text("a * b * c")), parse("a * b * c"))
  }

  @Test fun anUnclosedMarkerIsLiteral() {
    assertEquals(listOf(text("**open and "), code("x")), parse("**open and `x`"))
    assertEquals(listOf(text("a `b")), parse("a `b"))
  }

  @Test fun anEscapedMarkerIsLiteralAndCannotClose() {
    assertEquals(listOf(text("*not italic*")), parse("\\*not italic\\*"))
    assertEquals(listOf(italic("a * b")), parse("*a \\* b*"))
  }

  @Test fun aBackslashBeforeAnOrdinaryCharacterIsKept() {
    assertEquals(listOf(text("C:\\Users")), parse("C:\\Users"))
  }

  @Test fun emboldenedMakesTextBoldAndLeavesCodeAlone() {
    assertEquals(
      listOf(bold("a "), code("b"), NoteSpan(" c", NoteStyle.BOLD_ITALIC)),
      AvecMoiNoteMarkdown.emboldened(listOf(text("a "), code("b"), italic(" c"))),
    )
  }

  // --- runs that close more than one emphasis (from trying it in the IDE) -------------------------

  @Test fun theLineThatDidNotRenderInTheIde() {
    // The reported case: a *** run has to close the inner italic AND the outer bold.
    assertEquals(
      listOf(
        NoteSpan("bold and italic", NoteStyle.BOLD_ITALIC), text(" and "), bold("bold with "),
        NoteSpan("italic inside", NoteStyle.BOLD_ITALIC),
      ),
      parse("***bold and italic*** and **bold with *italic inside***"),
    )
  }

  @Test fun anItalicRunInsideBoldCanOpenTheSpanToo() {
    assertEquals(listOf(NoteSpan("italic", NoteStyle.BOLD_ITALIC), bold(" then bold")), parse("***italic* then bold**"))
  }

  @Test fun anOpenerWithoutAMatchingCloserLeavesItsExtraCharsLiteral() {
    // Three open, two close: the first * is left over, the rest is bold.
    assertEquals(listOf(text("*"), bold("bold with *italic inside")), parse("***bold with *italic inside**"))
    assertEquals(listOf(text("*"), italic("a")), parse("**a*"))
  }

  @Test fun aLongerCloserDoesNotStealFromItsOwnPair() {
    // The ** after y pairs with the ** before it; the lone * never closes.
    assertEquals(listOf(text("*x "), bold("y")), parse("*x **y**"))
  }

  @Test fun underscoreItalicInOrdinaryProse() {
    assertEquals(listOf(text("an "), italic("underscore italic"), text(" word.")), parse("an _underscore italic_ word."))
    assertEquals(listOf(text("("), italic("aside"), text(")")), parse("(_aside_)"))
    assertEquals(listOf(text("- "), italic("x")), parse("- _x_"))
  }
}
