package net.sjrx.intellij.plugins.avecmoi

/**
 * The note-inlay text layout: soft-wrapping and single-line collapse. Pulled out of the renderer so
 * it's pure and unit-testable — it takes a [widthOf] measurer (pixels for a string) and a [budget]
 * (pixels available) instead of an editor/FontMetrics, so tests can pass a simple char-count width.
 */
object AvecMoiNoteWrap {

  /**
   * The note split into display lines. The note's own newlines are hard breaks (paragraphs/lists) and
   * are kept — blank lines included. Each logical line is classified [isPreformatted] (code): a
   * preformatted line is rendered verbatim (leading indentation and interior spacing preserved),
   * truncated to [budget] rather than reflowed; every other line is prose and is greedily word-wrapped
   * to [budget]. Triple-backtick fence lines delimit preformatted regions and are swallowed (not
   * drawn). Leading/trailing blank lines and per-line trailing whitespace are trimmed so a note that
   * ends in a newline doesn't render empty rows. A single word wider than the budget is left to
   * overflow rather than being chopped.
   */
  fun wrap(note: String, budget: Int, widthOf: (String) -> Int): List<String> {
    val out = ArrayList<String>()
    var inFence = false
    for (raw in trimBlankEdges(note.split("\n"))) {
      if (isFenceMarker(raw)) {
        inFence = !inFence
        continue // the ``` line itself is a delimiter, not content — don't render it.
      }
      val logical = raw.trimEnd()
      if (isPreformatted(logical, inFence)) {
        out.add(truncateToWidth(logical, budget, widthOf)) // leading whitespace kept verbatim.
        continue
      }
      if (logical.isBlank()) {
        out.add("")
        continue
      }
      val line = StringBuilder()
      for (word in logical.split(" ")) {
        val candidate = if (line.isEmpty()) word else "$line $word"
        if (line.isEmpty() || widthOf(candidate) <= budget) {
          line.setLength(0)
          line.append(candidate)
        } else {
          out.add(line.toString())
          line.setLength(0)
          line.append(word)
        }
      }
      out.add(line.toString())
    }
    return out.ifEmpty { listOf("") }
  }

  /** Drops leading and trailing blank lines so a note that begins/ends in newlines renders no empty rows. */
  private fun trimBlankEdges(lines: List<String>): List<String> =
    lines.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }

  /** True if [raw] is a code-fence delimiter (a line whose first non-space content is ```, info-string allowed). */
  private fun isFenceMarker(raw: String): Boolean =
    raw.trimStart().startsWith("```")

  /**
   * True if [logical] should be rendered verbatim as code rather than word-wrapped as prose: either
   * we're currently inside a ``` fence, or the line is indented (agents set code off from prose by
   * indenting it). This is the whole classifier — see the handoff discussion for why indentation is
   * the load-bearing signal and fences are the belt-and-suspenders second signal.
   */
  private fun isPreformatted(logical: String, inFence: Boolean): Boolean =
    inFence || logical.startsWith(" ") || logical.startsWith("\t")

  /**
   * A preformatted line clipped to [budget] pixels, keeping its leading whitespace. If it already
   * fits, it's returned unchanged; otherwise trailing characters are dropped and a "…" appended.
   * Mirror [collapse]'s ellipsis loop, but drop from the END so the leading indent survives.
   */
  private fun truncateToWidth(logical: String, budget: Int, widthOf: (String) -> Int): String {
    if (widthOf(logical) <= budget) return logical
    var s = logical
    while (s.isNotEmpty() && widthOf("$s…") > budget) s = s.dropLast(1)
    return "$s…"
  }

  /** The note's first non-blank line, ellipsized to one [budget]-wide line, with "…" if there's more. */
  fun collapse(note: String, budget: Int, widthOf: (String) -> Int): String {
    val first = note.split("\n").firstOrNull { it.isNotBlank() }?.trim() ?: ""
    val hasMore = note.trim() != first // any content beyond that first line
    if (!hasMore && widthOf(first) <= budget) return first
    var s = first
    while (s.isNotEmpty() && widthOf("$s…") > budget) s = s.dropLast(1)
    return "$s…"
  }
}
