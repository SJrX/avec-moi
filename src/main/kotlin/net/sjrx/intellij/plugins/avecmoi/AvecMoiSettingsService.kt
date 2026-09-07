package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.annotations.XCollection

/** What happens to the target terminal when a quick action is sent to the agent. */
enum class TerminalRevealBehavior(val label: String) {
  SHOW("Show terminal"),
  SHOW_AND_FOCUS("Show and focus terminal"),
  NONE("Do nothing"),
}

/** Which handoff notes to float above their line in the editor (as block inlays). */
enum class EditorNoteScope(val label: String) {
  ALL("All handoffs in the file"),
  CURRENT("Only the current handoff"),
  OFF("Off"),
}

/** The human's self-reported familiarity with a subject (a language, framework, area, …). */
enum class Familiarity(val label: String) {
  NEW("New"),
  RUSTY("Rusty"),
  COMFORTABLE("Comfortable"),
  FLUENT("Fluent");

  override fun toString(): String = label
}

/**
 * How much the human WANTS to engage with a subject — orthogonal to skill. Low interest means "just
 * do it" regardless of familiarity (they don't care to learn or own it); high means hand them the
 * load-bearing parts even where they're new.
 */
enum class Interest(val label: String) {
  KEEN("Keen"),
  NEUTRAL("Neutral"),
  UNINTERESTED("Not interested");

  override fun toString(): String = label
}

/** How thorough the agent should be when the human hits "Step Done". */
enum class ReviewLevel(val label: String, val guidanceLine: String) {
  LIGHT(
    "Light",
    "keep it light — a syntax + logic sanity check; do NOT run the full test suite or nitpick style unless the human asks.",
  ),
  NORMAL(
    "Normal",
    "read the change for correctness and obvious problems; quick checks are fine, but don't turn it into a full QA pass unless warranted.",
  ),
  THOROUGH(
    "Thorough",
    "review carefully — run the tests, check edge cases and error paths, and flag style/design concerns.",
  ),
}

/**
 * Whether a familiarity row applies to THIS project only or to every project. Global rows persist
 * app-wide (a fact about the developer, like "fluent in Kotlin"); project rows override them here
 * (e.g. "rusty in this project's ancient Kotlin"). The scope decides which store a row is saved in.
 */
enum class FamiliarityScope(val label: String) {
  PROJECT("This project"),
  GLOBAL("All projects");

  override fun toString(): String = label
}

/** One row of the familiarity table: a subject, how familiar the human is, how much they care, and scope. */
class FamiliarityEntry {
  var subject: String = ""
  var level: Familiarity = Familiarity.COMFORTABLE
  var interest: Interest = Interest.NEUTRAL
  var scope: FamiliarityScope = FamiliarityScope.PROJECT

  fun copy(): FamiliarityEntry = FamiliarityEntry().also {
    it.subject = subject; it.level = level; it.interest = interest; it.scope = scope
  }

  override fun equals(other: Any?): Boolean =
    other is FamiliarityEntry &&
      other.subject == subject && other.level == level && other.interest == interest && other.scope == scope

  override fun hashCode(): Int =
    ((subject.hashCode() * 31 + level.hashCode()) * 31 + interest.hashCode()) * 31 + scope.hashCode()
}

/**
 * Per-project avec-moi settings, persisted at the workspace level (per-user, not in VCS). Holds the
 * quick-action behavior and the agent-guidance knobs (goal, familiarity, review depth, extra
 * context) that compose the project brief the agent fetches via get_avecmoi_guidance.
 */
@Service(Service.Level.PROJECT)
@State(name = "AvecMoiSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class AvecMoiSettingsService : PersistentStateComponent<AvecMoiSettingsService.State> {

  class State {
    /** What to do with the terminal after sending a quick action to the agent. */
    var terminalOnSend: TerminalRevealBehavior = TerminalRevealBehavior.SHOW

    /** Which handoff notes to show above their line in the editor. */
    var editorNoteScope: EditorNoteScope = EditorNoteScope.ALL

    /**
     * Max width, in columns, that a note line soft-wraps at. 0 = no cap (wrap to the editor's visible
     * width). A positive value caps the wrap at min(editor width, N columns) so notes don't stretch
     * uncomfortably wide on a big monitor.
     */
    var noteWrapMaxColumns: Int = 0

    /** What the human is working toward on this project (freeform). */
    var goal: String = ""

    /** How thorough review should be on "Step Done". */
    var reviewLevel: ReviewLevel = ReviewLevel.NORMAL

    /** Extra guidance the human wants appended verbatim to the brief. */
    var extraContext: String = ""

    @get:XCollection(style = XCollection.Style.v2)
    var familiarity: MutableList<FamiliarityEntry> = mutableListOf()
  }

  private var state = State()

  override fun getState(): State = state

  override fun loadState(state: State) {
    this.state = state
  }

  var terminalOnSend: TerminalRevealBehavior
    get() = state.terminalOnSend
    set(value) { state.terminalOnSend = value }

  var editorNoteScope: EditorNoteScope
    get() = state.editorNoteScope
    set(value) { state.editorNoteScope = value }

  var noteWrapMaxColumns: Int
    get() = state.noteWrapMaxColumns
    set(value) { state.noteWrapMaxColumns = value }

  var goal: String
    get() = state.goal
    set(value) { state.goal = value }

  var reviewLevel: ReviewLevel
    get() = state.reviewLevel
    set(value) { state.reviewLevel = value }

  var extraContext: String
    get() = state.extraContext
    set(value) { state.extraContext = value }

  var familiarity: MutableList<FamiliarityEntry>
    get() = state.familiarity
    set(value) { state.familiarity = value }

  /**
   * The composed per-project brief the agent fetches (goal + familiarity + review + extra context).
   * Familiarity merges the app-wide GLOBAL rows with this project's rows (project overrides by subject).
   */
  fun guidance(): String {
    val effective = AvecMoiGuidance.effectiveFamiliarity(service<AvecMoiGlobalSettings>().familiarity, state.familiarity)
    return AvecMoiGuidance.composeBrief(state.goal, effective, state.reviewLevel, state.extraContext)
  }
}
