package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.TextAttributesKey

object AvecMoiColors {
  /**
   * Background/foreground of the handoff-note inlay. Falls back to the editor's folded-text style
   * (subtle, theme-aware, readable) out of the box, and is user-editable in
   * Settings | Editor | Color Scheme | Avec Moi (see AvecMoiColorSettingsPage).
   */
  val NOTE_INLAY: TextAttributesKey =
    TextAttributesKey.createTextAttributesKey("AVEC_MOI_NOTE_INLAY", EditorColors.FOLDED_TEXT_ATTRIBUTES)
}
