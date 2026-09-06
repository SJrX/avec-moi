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
  override fun getDemoText(): String = "Handoff notes float above their line in the editor as inlays."
  override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

  companion object {
    private val DESCRIPTORS = arrayOf(AttributesDescriptor("Handoff note inlay", AvecMoiColors.NOTE_INLAY))
  }
}
