package net.sjrx.intellij.plugins.avecmoi

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

  // The preview can't render a real inlay, so style a tagged phrase with the same attribute — it
  // shows the exact bg/fg the note inlay uses, which is what you're editing here.
  override fun getDemoText(): String =
    "Handoff notes float above their line in the editor as inlays, e.g.\n\n" +
      "<note>implement the partition step here — pivot on the last element,\n" +
      "then recurse on the two halves</note>"

  override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> =
    mapOf("note" to AvecMoiColors.NOTE_INLAY)

  companion object {
    private val DESCRIPTORS = arrayOf(AttributesDescriptor("Handoff note inlay", AvecMoiColors.NOTE_INLAY))
  }
}
