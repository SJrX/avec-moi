package net.sjrx.intellij.plugins.avecmoi

import org.junit.Assert.assertEquals
import org.junit.Test

/** Each handoff kind is told apart by its own icon (issue #32). */
class HandoffKindIconTest {

  @Test fun everyKindHasADistinctIcon() {
    val icons = HandoffKind.entries.map { it.icon }
    assertEquals("kinds sharing an icon: ${HandoffKind.entries}", icons.size, icons.toSet().size)
  }
}
