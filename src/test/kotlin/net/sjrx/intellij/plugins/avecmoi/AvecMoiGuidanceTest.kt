package net.sjrx.intellij.plugins.avecmoi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guidance-brief composition — the text the agent fetches. Pure logic (no platform): empty
 * sections drop out, the familiarity table renders, and the review-depth line always appears.
 */
class AvecMoiGuidanceTest {

  private fun entry(subject: String, level: Familiarity, interest: Interest = Interest.NEUTRAL) =
    FamiliarityEntry().also { it.subject = subject; it.level = level; it.interest = interest }

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
  fun eachRowShowsBothFamiliarityAndInterest() {
    val brief = AvecMoiGuidance.composeBrief(
      "",
      listOf(
        entry("Kotlin", Familiarity.FLUENT, Interest.KEEN),
        entry("Regex", Familiarity.NEW, Interest.UNINTERESTED),
      ),
      ReviewLevel.NORMAL,
      "",
    )
    assertTrue(brief, brief.contains("Kotlin: fluent (keen)"))
    assertTrue(brief, brief.contains("Regex: new (not interested)"))
  }

  @Test
  fun theInterestAxisIsExplainedSoLowInterestMeansJustDoIt() {
    // The whole point of the axis: don't force a teaching moment on something they don't care about.
    val brief = AvecMoiGuidance.composeBrief(
      "",
      listOf(entry("Build scripts", Familiarity.NEW, Interest.UNINTERESTED)),
      ReviewLevel.NORMAL,
      "",
    )
    assertTrue("interest must be framed as an axis: $brief", brief.contains("INTEREST"))
    assertTrue("low interest → handle it yourself: $brief", brief.contains("just handle it yourself"))
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

  // --- global/project familiarity merge -------------------------------------------------------

  @Test
  fun effectiveFamiliarityUnionsGlobalAndProjectRows() {
    val effective = AvecMoiGuidance.effectiveFamiliarity(
      global = listOf(entry("Kotlin", Familiarity.FLUENT)),
      project = listOf(entry("This repo's DSL", Familiarity.NEW)),
    )
    assertEquals(listOf("Kotlin", "This repo's DSL"), effective.map { it.subject })
  }

  @Test
  fun aProjectRowOverridesAGlobalRowOfTheSameSubject() {
    // Globally fluent in Kotlin, but rusty in THIS project's ancient Kotlin — project wins, in place.
    val effective = AvecMoiGuidance.effectiveFamiliarity(
      global = listOf(entry("Kotlin", Familiarity.FLUENT, Interest.KEEN), entry("SQL", Familiarity.COMFORTABLE)),
      project = listOf(entry(" kotlin ", Familiarity.RUSTY, Interest.UNINTERESTED)), // padded + different case
    )
    // The override keeps the global row's POSITION but is the project row wholesale (its text wins too).
    assertEquals("override stays in the global row's slot", listOf("kotlin", "sql"), effective.map { it.subject.trim().lowercase() })
    val kotlin = effective.first { it.subject.trim().equals("kotlin", ignoreCase = true) }
    assertEquals(Familiarity.RUSTY, kotlin.level)
    assertEquals(Interest.UNINTERESTED, kotlin.interest)
  }

  @Test
  fun blankSubjectRowsAreDroppedFromTheMerge() {
    val effective = AvecMoiGuidance.effectiveFamiliarity(
      global = listOf(entry("   ", Familiarity.NEW)),
      project = listOf(entry("Kotlin", Familiarity.FLUENT), entry("", Familiarity.NEW)),
    )
    assertEquals(listOf("Kotlin"), effective.map { it.subject })
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
