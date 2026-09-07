package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.vcs.FileStatus
import java.awt.Color

/**
 * The two colors of the handoff-note inlay, exposed under Settings | Editor | Color Scheme | Avec Moi.
 * They're [ColorKey]s (not text-attributes) so the foreground can *inherit* a theme color live.
 */
object AvecMoiColors {

  /**
   * Note text. Inherits the IDE's "ignored / non-project file" status color, so notes read in the
   * same amber the active theme uses for out-of-project files — live per theme (New UI: #8C4F00
   * light, #D69A6B dark) — and stays overridable in the color scheme.
   */
  val NOTE_FOREGROUND: ColorKey =
    ColorKey.createColorKeyWithFallback("AVEC_MOI_NOTE_FOREGROUND", FileStatus.IGNORED.colorKey)

  /**
   * The chip behind the note. Defaults to a faint wash of the same amber (low alpha, so the inherited
   * amber text stays readable); clear it in the scheme for text-only notes, or set any tint you like.
   */
  val NOTE_BACKGROUND: ColorKey =
    ColorKey.createColorKey("AVEC_MOI_NOTE_BACKGROUND", Color(0x8C, 0x4F, 0x00, 28))
}
