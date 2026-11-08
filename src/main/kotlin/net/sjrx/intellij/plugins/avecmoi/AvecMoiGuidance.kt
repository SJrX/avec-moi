package net.sjrx.intellij.plugins.avecmoi

/**
 * Composes the per-project guidance brief the agent fetches via get_avecmoi_guidance, from the
 * human's settings. Pure and platform-free so the composition is unit-testable. Sections with no
 * content are omitted; review depth is always stated.
 */
object AvecMoiGuidance {

  /**
   * The effective familiarity for a project: global rows (the developer's defaults) plus this
   * project's rows, where a project row OVERRIDES a global row with the same subject (trimmed,
   * case-insensitive). Global-then-project order, project-only rows appended. Pure/testable.
   */
  fun effectiveFamiliarity(
    global: List<FamiliarityEntry>,
    project: List<FamiliarityEntry>,
  ): List<FamiliarityEntry> {
    val byKey = LinkedHashMap<String, FamiliarityEntry>()
    (global + project)
      .filter { it.subject.isNotBlank() }
      .forEach { byKey[it.subject.trim().lowercase()] = it } // project comes second, so it overrides
    return byKey.values.toList()
  }

  /** One agent-requested change to a familiarity row; null fields leave an existing row's value alone. */
  data class FamiliarityChange(val subject: String, val level: Familiarity?, val interest: Interest?)

  /** The rows after [applyFamiliarity], plus what happened, for the tool's reply. */
  data class FamiliarityUpdate(
    val rows: List<FamiliarityEntry>,
    val added: List<String>,
    val updated: List<String>,
    val removed: List<String>,
    val rejected: List<String>,
  )

  /**
   * Apply agent-requested [changes] and [removals] to one store's [rows] (a single scope), matching
   * subjects trimmed and case-insensitively. An existing row gets only the fields that are set. A new
   * row needs a level, except when its interest is "not interested", where the level doesn't matter
   * (the agent just handles it) and it's stored as comfortable; a new row with neither is [rejected]
   * rather than guessed. Pure/testable; [rows] isn't modified.
   */
  fun applyFamiliarity(
    rows: List<FamiliarityEntry>,
    changes: List<FamiliarityChange>,
    removals: List<String>,
    scope: FamiliarityScope,
  ): FamiliarityUpdate {
    fun key(subject: String) = subject.trim().lowercase()
    val out = rows.map { it.copy() }.toMutableList()
    val added = ArrayList<String>()
    val updated = ArrayList<String>()
    val removed = ArrayList<String>()
    val rejected = ArrayList<String>()
    for (c in changes) {
      val subject = c.subject.trim()
      if (subject.isEmpty()) continue
      val existing = out.firstOrNull { key(it.subject) == key(subject) }
      if (existing != null) {
        c.level?.let { existing.level = it }
        c.interest?.let { existing.interest = it }
        updated.add(existing.subject)
        continue
      }
      val level = c.level ?: if (c.interest == Interest.UNINTERESTED) Familiarity.COMFORTABLE else null
      if (level == null) {
        rejected.add(subject)
        continue
      }
      out.add(
        FamiliarityEntry().also {
          it.subject = subject
          it.level = level
          it.interest = c.interest ?: Interest.NEUTRAL
          it.scope = scope
        },
      )
      added.add(subject)
    }
    for (r in removals) {
      val hit = out.firstOrNull { key(it.subject) == key(r) } ?: continue
      out.remove(hit)
      removed.add(hit.subject)
    }
    return FamiliarityUpdate(out, added, updated, removed, rejected)
  }

  /**
   * [existing] extra context with [addition] added as a new paragraph, so an agent's note never
   * rewrites what the human typed. Blank [existing] yields just the addition. Pure/testable.
   */
  fun appendExtraContext(existing: String, addition: String): String {
    val add = addition.trim()
    val base = existing.trimEnd()
    return when {
      add.isEmpty() -> existing
      base.isEmpty() -> add
      else -> "$base\n$add"
    }
  }

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
      appendLine("The human's self-reported familiarity, and how much they want to implement themselves:")
      reported.forEach {
        // The scope tells the agent which store set_familiarity should edit for this row.
        val scope = if (it.scope == FamiliarityScope.GLOBAL) " [all projects]" else " [this project]"
        appendLine("  - ${it.subject.trim()}: ${it.level.label.lowercase()} (${it.interest.label.lowercase()})$scope")
      }
      appendLine(
        "Two axes: FAMILIARITY is how much they know; IMPLEMENTATION INTEREST is how much they WANT to " +
          "write it themselves. Implementation interest generally decides WHETHER to hand a task back; " +
          "familiarity decides HOW MUCH to scaffold when you do.",
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
