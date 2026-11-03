package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.editor.markup.TextAttributes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The phrasebook's row layout and highlight clipping — pure, so plain JUnit (no platform). As in
 * [AvecMoiNoteWrapTest] the width measurer is string length, so `budget` reads as a column count.
 */
class AvecMoiPhrasebookLayoutTest {

  private val byChars: (String) -> Int = { it.length }
  private fun layout(budget: Int, vararg phrases: Phrase) =
    AvecMoiPhrasebook.layout(phrases.toList(), budget, byChars, byChars).map { it.code to it.gloss }

  // --- layout ----------------------------------------------------------------------------------

  @Test fun codeAndGlossThatFitShareARow() {
    // "x ?: y" (6) + "  — elvis" (9) = 15 <= 40
    assertEquals(listOf("x ?: y" to "elvis"), layout(40, Phrase("x ?: y", "elvis")))
  }

  @Test fun aGlossThatDoesNotFitBesideItsCodeWrapsOntoItsOwnRow() {
    // 6 + 9 = 15 > 12, so the gloss moves down.
    assertEquals(listOf("x ?: y" to "", "" to "elvis"), layout(12, Phrase("x ?: y", "elvis")))
  }

  @Test fun aPhraseWithoutAGlossIsJustItsCode() {
    assertEquals(listOf("xs.map { it }" to ""), layout(40, Phrase("xs.map { it }", "  ")))
  }

  @Test fun codeWiderThanTheBudgetIsClippedWithAnEllipsis() {
    assertEquals(listOf("abcdefghi…" to ""), layout(10, Phrase("abcdefghijklmnop")))
  }

  @Test fun eachPhraseKeepsItsOwnRowsInOrder() {
    // Fixture Setup
    val first = Phrase("a", "one")
    val second = Phrase("b", "two")

    // Exercise SUT
    val rows = AvecMoiPhrasebook.layout(listOf(first, second), 40, byChars, byChars)

    // Verification — and each code row points back at its source phrase, for highlighting
    assertEquals(listOf("a" to "one", "b" to "two"), rows.map { it.code to it.gloss })
    assertEquals(listOf(first, second), rows.map { it.phrase })
  }

  // --- clip ------------------------------------------------------------------------------------

  private val keyword = TextAttributes()
  private val segments = listOf(
    AvecMoiPhrasebook.Segment("return", keyword),
    AvecMoiPhrasebook.Segment(" ", null),
    AvecMoiPhrasebook.Segment("null", keyword),
  )

  @Test fun unclippedCodeKeepsAllItsSegments() {
    assertEquals(segments, AvecMoiPhrasebook.clip(segments, "return null"))
  }

  @Test fun clippedCodeKeepsColorsUpToTheClipThenEndsInAPlainEllipsis() {
    // Exercise SUT — the layout clipped "return null" to "return n…"
    val clipped = AvecMoiPhrasebook.clip(segments, "return n…")

    // Verification
    assertEquals(listOf("return", " ", "n", "…"), clipped.map { it.text })
    assertEquals(listOf(keyword, null, keyword, null), clipped.map { it.attributes })
  }
}
