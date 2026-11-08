package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.image.BufferedImage

/**
 * The note inlay drawing a Markdown note: the real inlay service and renderer against a real
 * light-fixture editor. Layout details are pinned by [AvecMoiNoteWrapTest]; this checks the renderer
 * sizes itself from that layout and paints styled spans without falling over.
 */
class AvecMoiNoteInlayTest : AbstractAvecMoiTest() {

  private val inlays get() = project.service<AvecMoiInlayService>()

  // Handoff files are resolved on the real disk, so keep this fixture's files there, not in memory.
  override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

  private fun noteInlay(note: String): Inlay<*> {
    myFixture.configureByText("Partition.kt", "fun a() {}\nfun partition() {}\n")
    ring.set(listOf(spec(file = myFixture.file.virtualFile.path, line = 2, note = note)))
    inlays.refreshAll()
    return myFixture.editor.inlayModel.getBlockElementsForVisualLine(1, true)
      .single { it.renderer.javaClass.simpleName == "NoteInlayRenderer" }
  }

  fun testAMarkdownNoteIsOneRowPerLaidOutLineAndPaints() {
    // Fixture Setup
    // language=Markdown
    val note = "## Partition\n- use `xs.partition { }`\n- keep it **stable**\n\n```\nval (a, b) = xs\n```"
    val inlay = noteInlay(note)

    // Exercise SUT
    val image = BufferedImage(inlay.widthInPixels, inlay.heightInPixels, BufferedImage.TYPE_INT_ARGB)
    val g: Graphics = image.createGraphics() // typed as Graphics: paint() is also overloaded for Graphics2D
    try {
      inlay.renderer.paint(inlay, g, Rectangle(0, 0, inlay.widthInPixels, inlay.heightInPixels), TextAttributes())
    } finally {
      g.dispose()
    }

    // Verification — heading, two bullets, blank line, one code line; the fence lines aren't drawn
    assertEquals((5 * myFixture.editor.lineHeight).toLong(), inlay.heightInPixels.toLong())
  }
}
