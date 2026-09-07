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
      appendLine("The human's self-reported familiarity, and how much they want to engage:")
      reported.forEach {
        appendLine("  - ${it.subject.trim()}: ${it.level.label.lowercase()} (${it.interest.label.lowercase()})")
      }
      appendLine(
        "Two axes: FAMILIARITY is how much they know; INTEREST is how much they WANT to do it " +
          "themselves. Interest generally decides WHETHER to hand a task back; familiarity decides HOW " +
          "MUCH to scaffold when you do.",
      )
      appendLine(
        "  - Not interested: just handle it yourself, whatever their skill — don't hand it back or " +
          "make them learn it.",
      )
      appendLine(
        "  - Keen: hand off the load-bearing parts even where they're new (scaffold more so they can " +
          "learn), and especially where they're fluent.",
      )
      appendLine(
        "  - Neutral: let familiarity decide — comfortable/fluent → hand off more and explain less; " +
          "new/rusty → scaffold more and hand back the instructive parts.",
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
