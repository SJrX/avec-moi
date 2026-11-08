package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.editor.colors.EditorFontType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Which editor font each run of a note gets, per the "Draw note text" setting. What matters is that
 * every kind of emphasis stays distinguishable from the surrounding text in both styles.
 */
class NoteTextStyleTest {

  @Test fun uprightDrawsMarkdownAsWritten() {
    val fonts = NoteStyle.entries.associateWith { NoteTextStyle.UPRIGHT.fontType(it) }
    assertEquals(
      mapOf(
        NoteStyle.TEXT to EditorFontType.PLAIN, NoteStyle.BOLD to EditorFontType.BOLD,
        NoteStyle.ITALIC to EditorFontType.ITALIC, NoteStyle.BOLD_ITALIC to EditorFontType.BOLD_ITALIC,
        NoteStyle.CODE to EditorFontType.PLAIN,
      ),
      fonts,
    )
  }

  @Test fun italicTogglesEmphasisAgainstTheItalicBase() {
    val fonts = NoteStyle.entries.associateWith { NoteTextStyle.ITALIC.fontType(it) }
    assertEquals(
      mapOf(
        NoteStyle.TEXT to EditorFontType.ITALIC, NoteStyle.BOLD to EditorFontType.BOLD_ITALIC,
        NoteStyle.ITALIC to EditorFontType.PLAIN, NoteStyle.BOLD_ITALIC to EditorFontType.BOLD,
        NoteStyle.CODE to EditorFontType.PLAIN,
      ),
      fonts,
    )
  }

  @Test fun emphasisNeverLooksLikeTheBaseTextInEitherStyle() {
    for (textStyle in NoteTextStyle.entries) {
      val base = textStyle.fontType(NoteStyle.TEXT)
      listOf(NoteStyle.BOLD, NoteStyle.ITALIC, NoteStyle.BOLD_ITALIC).forEach {
        assertNotEquals("$textStyle $it", base, textStyle.fontType(it))
      }
    }
  }
}
