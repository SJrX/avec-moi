package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.editor.colors.EditorColorsManager

/**
 * Phrasebook syntax highlighting against the real platform: the file's own syntax highlighter and
 * the IDE's color scheme. Uses XML because it ships with the core platform (the test sandbox loads no
 * language plugins), but the code path is the same for Kotlin, Java, Python and so on.
 */
class AvecMoiPhrasebookHighlightTest : AbstractAvecMoiTest() {

  private val scheme get() = EditorColorsManager.getInstance().globalScheme

  fun testCodeIsSplitIntoColoredTokensUsingTheFilesHighlighter() {
    // Fixture Setup — a handoff file of a type with a real highlighter
    val file = myFixture.configureByText("layout.xml", "<root/>").virtualFile

    // Exercise SUT
    val segments = AvecMoiPhrasebook.highlight("""<item name="x"/>""", project, file, scheme)

    // Verification — split into several tokens that rebuild the code exactly, some of them colored
    assertTrue("expected several tokens, got $segments", segments.size > 1)
    assertEquals("""<item name="x"/>""", segments.joinToString("") { it.text })
    assertTrue("expected some token to carry a color", segments.any { it.attributes?.foregroundColor != null })
  }

  fun testWithoutAFileTheCodeIsOnePlainRun() {
    // Exercise SUT
    val segments = AvecMoiPhrasebook.highlight("val m = marker ?: return null", project, null, scheme)

    // Verification
    assertEquals(listOf(AvecMoiPhrasebook.Segment("val m = marker ?: return null", null)), segments)
  }
}
