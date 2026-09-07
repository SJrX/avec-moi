package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.editor.colors.TextAttributesKey

/**
 * The handoff-note inlay style — one Text Attributes key carrying both a foreground (note text) and a
 * background (the chip). It's a text-attributes key (not a ColorKey) so it drives the live sample in
 * the color-settings demo. Per-theme defaults ship as bundled schemes (colorSchemes/AvecMoi*.xml,
 * registered via additionalTextAttributes in plugin.xml): amber, echoing the IDE's ignored /
 * non-project file color. Everything stays editable in Settings | Editor | Color Scheme | Avec Moi.
 */
object AvecMoiColors {
  val NOTE_INLAY: TextAttributesKey = TextAttributesKey.createTextAttributesKey("AVEC_MOI_NOTE_INLAY")
}
