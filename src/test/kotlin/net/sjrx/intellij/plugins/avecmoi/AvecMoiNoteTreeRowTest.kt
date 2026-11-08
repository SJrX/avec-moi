package net.sjrx.intellij.plugins.avecmoi

import com.intellij.ui.SimpleTextAttributes.STYLE_BOLD
import com.intellij.ui.SimpleTextAttributes.STYLE_ITALIC
import com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN
import org.junit.Assert.assertEquals
import org.junit.Test

/** The note's one-row summary in the tool window tree. Pure, so plain JUnit with a char-count width. */
class AvecMoiNoteTreeRowTest {

  private val chars: NoteMeasure = { text, _ -> text.length }

  private fun row(note: String, budget: Int = 1000, textStyle: NoteTextStyle = NoteTextStyle.UPRIGHT) =
    AvecMoiNoteTreeRow.fragments(note, budget, chars, textStyle)

  @Test fun aShortOneLineNoteIsShownWhole() {
    assertEquals(listOf(NoteTreeFragment("Parse the header", STYLE_PLAIN)), row("Parse the header"))
  }

  @Test fun aLongNoteIsCutToTheBudgetWithAnEllipsis() {
    val shown = row("Parse the header and validate every field before storing it", budget = 20)

    assertEquals("Parse the header an…", shown.joinToString("") { it.text })
  }

  @Test fun onlyTheFirstLineIsShownAndTheRestIsMarkedWithAnEllipsis() {
    val shown = row("Parse the header.\n\nThen validate it.")

    assertEquals("Parse the header.…", shown.joinToString("") { it.text })
  }

  @Test fun markdownIsRenderedNotShownAsSyntax() {
    val shown = row("Use **bold** and *italic* and `code`")

    assertEquals(
      listOf(
        NoteTreeFragment("Use ", STYLE_PLAIN), NoteTreeFragment("bold", STYLE_BOLD),
        NoteTreeFragment(" and ", STYLE_PLAIN), NoteTreeFragment("italic", STYLE_ITALIC),
        NoteTreeFragment(" and ", STYLE_PLAIN), NoteTreeFragment("code", STYLE_PLAIN),
      ),
      shown,
    )
  }

  @Test fun theItalicSettingFlipsTheSlantLikeTheInlays() {
    val shown = row("plain *emphasis*", textStyle = NoteTextStyle.ITALIC)

    assertEquals(
      listOf(NoteTreeFragment("plain ", STYLE_ITALIC), NoteTreeFragment("emphasis", STYLE_PLAIN)),
      shown,
    )
  }

  @Test fun aBlankNoteSaysSo() {
    assertEquals(listOf(NoteTreeFragment(AvecMoiNoteTreeRow.NO_NOTE, STYLE_ITALIC)), row("  \n "))
  }
}
