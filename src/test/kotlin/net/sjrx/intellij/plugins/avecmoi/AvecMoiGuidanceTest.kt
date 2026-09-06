package net.sjrx.intellij.plugins.avecmoi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guidance-brief composition — the text the agent fetches. Pure logic (no platform): empty
 * sections drop out, the familiarity table renders, and the review-depth line always appears.
 */
class AvecMoiGuidanceTest {

  private fun entry(subject: String, level: Familiarity) =
    FamiliarityEntry().also { it.subject = subject; it.level = level }

  @Test
  fun anEmptyConfigStillStatesReviewDepthAndOmitsTheRest() {
    val brief = AvecMoiGuidance.composeBrief("", emptyList(), ReviewLevel.NORMAL, "")
    assertTrue("review depth always present: $brief", brief.contains("REVIEW DEPTH"))
    assertFalse("no goal section when goal is blank: $brief", brief.contains("GOAL:"))
    assertFalse("no familiarity section when empty: $brief", brief.contains("familiarity"))
    assertFalse("no extra-context section when blank: $brief", brief.contains("Additional context"))
  }

  @Test
  fun theGoalIsIncludedWhenSet() {
    val brief = AvecMoiGuidance.composeBrief("Ship feature X", emptyList(), ReviewLevel.LIGHT, "")
    assertTrue(brief.contains("GOAL: Ship feature X"))
  }

  @Test
  fun theFamiliarityTableRendersEachReportedSubject() {
    val brief = AvecMoiGuidance.composeBrief(
      "",
      listOf(entry("Kotlin", Familiarity.RUSTY), entry("Spring Boot", Familiarity.NEW)),
      ReviewLevel.NORMAL,
      "",
    )
    assertTrue(brief.contains("Kotlin: rusty"))
    assertTrue(brief.contains("Spring Boot: new"))
  }

  @Test
  fun blankSubjectRowsAreSkipped() {
    val brief = AvecMoiGuidance.composeBrief(
      "",
      listOf(entry("Kotlin", Familiarity.RUSTY), entry("   ", Familiarity.NEW)),
      ReviewLevel.NORMAL,
      "",
    )
    assertTrue(brief.contains("Kotlin: rusty"))
    assertFalse("a blank-subject row must not render its level: $brief", brief.contains(": new"))
  }

  @Test
  fun theReviewDepthLineReflectsTheChosenLevel() {
    val light = AvecMoiGuidance.composeBrief("", emptyList(), ReviewLevel.LIGHT, "")
    assertTrue(light.contains("keep it light"))
    val thorough = AvecMoiGuidance.composeBrief("", emptyList(), ReviewLevel.THOROUGH, "")
    assertTrue(thorough.contains("run the tests"))
  }

  @Test
  fun extraContextIsAppendedVerbatim() {
    val brief = AvecMoiGuidance.composeBrief("", emptyList(), ReviewLevel.NORMAL, "Prefer immutable data.")
    assertTrue(brief.contains("Additional context from the human:"))
    assertTrue(brief.contains("Prefer immutable data."))
  }
}
