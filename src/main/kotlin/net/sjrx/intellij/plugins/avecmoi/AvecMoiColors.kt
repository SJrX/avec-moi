package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.editor.colors.TextAttributesKey

/**
 * The handoff-note inlay styles — one Text Attributes key per state, each carrying a foreground (note
 * text) and a background (the chip). They're text-attributes keys (not ColorKeys) so they drive the
 * live samples in the color-settings demo. Per-theme defaults ship as bundled schemes
 * (colorSchemes/AvecMoi*.xml, registered via additionalTextAttributes in plugin.xml), echoing the
 * IDE's file colors: a todo note wears the "non-project / ignored" amber-on-yellow, a done note the
 * "added" green-on-green. Everything stays editable in Settings | Editor | Color Scheme | Avec Moi.
 */
object AvecMoiColors {
  /** A todo (not-yet-done) handoff note. */
  val NOTE_INLAY: TextAttributesKey = TextAttributesKey.createTextAttributesKey("AVEC_MOI_NOTE_INLAY")

  /** A completed handoff note (the human implemented it and the agent marked it done). */
  val NOTE_INLAY_DONE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("AVEC_MOI_NOTE_INLAY_DONE")

  /** Info note — a read item (context to study) or a reference bookmark. Blue, echoing a file color. */
  val NOTE_INLAY_INFO: TextAttributesKey = TextAttributesKey.createTextAttributesKey("AVEC_MOI_NOTE_INLAY_INFO")
}
