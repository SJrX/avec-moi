package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

/** Exposes the handoff-note inlay color under Settings | Editor | Color Scheme | Avec Moi. */
class AvecMoiColorSettingsPage : ColorSettingsPage {
  override fun getDisplayName(): String = "Avec Moi"
  override fun getIcon(): Icon? = null
  override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS
  override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
  override fun getHighlighter(): SyntaxHighlighter = PlainSyntaxHighlighter()

  // The preview can't render a real inlay, so style tagged phrases with the same attributes — they
  // show the exact bg/fg (and italic) the note inlays use, both a done and a todo one. The rest is
  // faked syntax coloring for flavor.
  override fun getDemoText(): String =
    """
    <comment>// avec-moi scaffolded this. The load-bearing part is all yours.</comment>
    <keyword>fun</keyword> <fn>greet</fn>(name: <keyword>String</keyword>): <keyword>String</keyword> {
        <note_done>TODO(human): done — you wrote this, I reviewed it, I marked it complete.</note_done>
        <keyword>return</keyword> <str>"Hello, ${'$'}name"</str>
    }

    <keyword>fun</keyword> <fn>getRandomNumber</fn>(): <keyword>Int</keyword> {
        <note>TODO(human): make this actually random.
        I did the imports and the return type — I left you the interesting part.</note>
        <note_info>REVIEW(human): magic number — pull this out into a named constant.</note_info>
        <keyword>return</keyword> <num>4</num>  <comment>// chosen by fair dice roll. guaranteed to be random.</comment>
    }
    """.trimIndent()

  override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> =
    mapOf(
      "note" to AvecMoiColors.NOTE_INLAY,
      "note_done" to AvecMoiColors.NOTE_INLAY_DONE,
      "note_info" to AvecMoiColors.NOTE_INLAY_INFO,
      "keyword" to DefaultLanguageHighlighterColors.KEYWORD,
      "fn" to DefaultLanguageHighlighterColors.FUNCTION_DECLARATION,
      "num" to DefaultLanguageHighlighterColors.NUMBER,
      "str" to DefaultLanguageHighlighterColors.STRING,
      "comment" to DefaultLanguageHighlighterColors.LINE_COMMENT,
    )

  companion object {
    private val DESCRIPTORS = arrayOf(
      AttributesDescriptor("Handoff note inlay (todo)", AvecMoiColors.NOTE_INLAY),
      AttributesDescriptor("Handoff note inlay (done)", AvecMoiColors.NOTE_INLAY_DONE),
      AttributesDescriptor("Handoff note inlay (review / reference)", AvecMoiColors.NOTE_INLAY_INFO),
    )
  }
}
