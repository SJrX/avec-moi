package net.sjrx.intellij.plugins.avecmoi

/**
 * The note-inlay text layout: soft-wrapping and single-line collapse. Pulled out of the renderer so
 * it's pure and unit-testable — it takes a [widthOf] measurer (pixels for a string) and a [budget]
 * (pixels available) instead of an editor/FontMetrics, so tests can pass a simple char-count width.
 */
object AvecMoiNoteWrap {

  /**
   * The note split into display lines. The note's own newlines are hard breaks (paragraphs/lists) and
   * are kept — blank lines included; each logical line is then greedily word-wrapped to [budget].
   * Leading/trailing blank lines and per-line trailing whitespace are trimmed so a note that ends in a
   * newline doesn't render empty rows. A single word wider than the budget is left to overflow rather
   * than being chopped.
   */
  fun wrap(note: String, budget: Int, widthOf: (String) -> Int): List<String> {
    val out = ArrayList<String>()
    for (logical in note.trim().split("\n")) {
      val stripped = logical.trimEnd()
      if (stripped.isBlank()) {
        out.add("")
        continue
      }
      val line = StringBuilder()
      for (word in stripped.split(" ")) {
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
