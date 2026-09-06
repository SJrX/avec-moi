package net.sjrx.intellij.plugins.avecmoi

/**
 * Composes the per-project guidance brief the agent fetches via get_avecmoi_guidance, from the
 * human's settings. Pure and platform-free so the composition is unit-testable. Sections with no
 * content are omitted; review depth is always stated.
 */
object AvecMoiGuidance {

  fun composeBrief(
    goal: String,
    familiarity: List<FamiliarityEntry>,
    review: ReviewLevel,
    extraContext: String,
  ): String = buildString {
    appendLine("How the human wants to work on this project (their words, via avec-moi):")

    if (goal.isNotBlank()) {
      appendLine()
      appendLine("GOAL: ${goal.trim()}")
    }

    val reported = familiarity.filter { it.subject.isNotBlank() }
    if (reported.isNotEmpty()) {
      appendLine()
      appendLine("The human's self-reported familiarity:")
      reported.forEach { appendLine("  - ${it.subject.trim()}: ${it.level.label.lowercase()}") }
      appendLine(
        "Calibrate to this — where they're comfortable/fluent, hand off more and explain less; " +
          "where they're new/rusty, scaffold more and hand back the instructive, load-bearing parts.",
      )
    }

    appendLine()
    appendLine("REVIEW DEPTH when the human hits \"Step Done\": ${review.guidanceLine}")

    if (extraContext.isNotBlank()) {
      appendLine()
      appendLine("Additional context from the human:")
      appendLine(extraContext.trim())
    }
  }.trim()
}
