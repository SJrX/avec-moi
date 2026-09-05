package net.sjrx.intellij.plugins.avecmoi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The directory→project routing rule (`bestMatchingBasePath`), which decides which open project an
 * agent's `projectPath` (its cwd) is talking to. Pure logic, so no platform — but it's fiddly (path
 * boundaries, nesting, trailing slashes), which is exactly why it's worth pinning.
 */
class ProjectPathMatchingTest {

  private fun match(path: String?, vararg bases: String): String? =
    AvecMoiMcpService.bestMatchingBasePath(bases.toList(), path)

  @Test
  fun exactPathMatchesItsBase() {
    assertEquals("/home/u/proj", match("/home/u/proj", "/home/u/proj"))
  }

  @Test
  fun aDirectoryInsideTheProjectMatchesTheBase() {
    assertEquals("/home/u/proj", match("/home/u/proj/src/main/kotlin", "/home/u/proj"))
  }

  @Test
  fun theDeepestContainingBaseWinsForNestedProjects() {
    // The cwd is inside both the outer repo and an inner sub-project; the inner one should win.
    assertEquals(
      "/home/u/proj/sub",
      match("/home/u/proj/sub/src", "/home/u/proj", "/home/u/proj/sub"),
    )
  }

  @Test
  fun aSiblingSharingANamePrefixDoesNotMatch() {
    // The classic bug: "/home/u/proj" must NOT swallow a cwd under "/home/u/proj-other".
    assertNull(match("/home/u/proj-other/src", "/home/u/proj"))
  }

  @Test
  fun trailingSlashesAreIgnoredOnBothSides() {
    assertEquals("/home/u/proj", match("/home/u/proj/", "/home/u/proj"))
    assertEquals("/home/u/proj", match("/home/u/proj", "/home/u/proj/"))
    assertEquals("/home/u/proj", match("/home/u/proj/src/", "/home/u/proj/"))
  }

  @Test
  fun noOpenProjectsYieldsNoMatch() {
    assertNull(match("/home/u/proj"))
  }

  @Test
  fun aBlankOrNullPathYieldsNoMatch() {
    assertNull(match(null, "/home/u/proj"))
    assertNull(match("", "/home/u/proj"))
    assertNull(match("   ", "/home/u/proj"))
  }

  @Test
  fun aPathAboveEveryProjectYieldsNoMatch() {
    assertNull(match("/home/u", "/home/u/proj", "/home/u/other"))
  }
}
