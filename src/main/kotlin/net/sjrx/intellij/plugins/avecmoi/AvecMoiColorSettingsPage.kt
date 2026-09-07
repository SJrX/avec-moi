package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

/**
 * Exposes the two handoff-note-inlay colors under Settings | Editor | Color Scheme | Avec Moi. They're
 * [ColorDescriptor]s (color pickers), not text-attributes, because the foreground inherits a theme
 * color ([AvecMoiColors.NOTE_FOREGROUND] → the ignored/non-project file status color). ColorKeys can't
 * drive the demo highlighting, so the demo below is flavor only — the real preview is the notes
 * themselves in an open editor.
 */
class AvecMoiColorSettingsPage : ColorSettingsPage {
  override fun getDisplayName(): String = "Avec Moi"
  override fun getIcon(): Icon? = null
  override fun getAttributeDescriptors(): Array<AttributesDescriptor> = emptyArray()
  override fun getColorDescriptors(): Array<ColorDescriptor> = COLORS
  override fun getHighlighter(): SyntaxHighlighter = PlainSyntaxHighlighter()

  override fun getDemoText(): String =
    """
    <comment>// avec-moi scaffolded this. The load-bearing part is all yours.</comment>
    <keyword>fun</keyword> <fn>getRandomNumber</fn>(): <keyword>Int</keyword> {
        <comment>// TODO(human): make this actually random — I left you the interesting part.</comment>
        <keyword>return</keyword> <num>4</num>  <comment>// chosen by fair dice roll. guaranteed to be random.</comment>
    }
    """.trimIndent()

  override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> =
    mapOf(
      "keyword" to DefaultLanguageHighlighterColors.KEYWORD,
      "fn" to DefaultLanguageHighlighterColors.FUNCTION_DECLARATION,
      "num" to DefaultLanguageHighlighterColors.NUMBER,
      "comment" to DefaultLanguageHighlighterColors.LINE_COMMENT,
    )

  companion object {
    private val COLORS = arrayOf(
      ColorDescriptor("Handoff note foreground (text)", AvecMoiColors.NOTE_FOREGROUND, ColorDescriptor.Kind.FOREGROUND),
      ColorDescriptor(
        "Handoff note background (chip)",
        AvecMoiColors.NOTE_BACKGROUND,
        ColorDescriptor.Kind.BACKGROUND_WITH_TRANSPARENCY,
      ),
    )
  }
}
