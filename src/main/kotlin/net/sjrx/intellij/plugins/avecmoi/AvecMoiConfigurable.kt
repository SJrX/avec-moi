package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import java.awt.Dimension
import javax.swing.Action
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.TableCellEditor

/**
 * Settings | Tools | Avec Moi: quick-action behavior plus the agent-guidance knobs (goal, a
 * self-reported familiarity table, review depth, extra context) that compose the project brief the
 * agent fetches. "View effective guidance" shows the whole thing (base instructions + brief).
 */
class AvecMoiConfigurable(private val project: Project) : BoundConfigurable("Avec Moi") {

  private val settings = project.service<AvecMoiSettingsService>()

  // The familiarity table isn't DSL-bindable, so it's synced manually in onReset/onApply/onIsModified.
  private val familiarityModel = ListTableModel<FamiliarityEntry>(SubjectColumn(), LevelColumn())
  private val familiarityTable = TableView(familiarityModel)

  override fun createPanel(): DialogPanel = panel {
    group("Quick Actions") {
      buttonsGroup("When you send a quick action (Step Done, Do This Step, Ask for a Hint), the target terminal will:") {
        TerminalRevealBehavior.entries.forEach { behavior ->
          row { radioButton(behavior.label, behavior) }
        }
      }.bind(settings::terminalOnSend)
    }

    group("Agent Guidance") {
      row {
        textArea().align(AlignX.FILL)
          .label("Goal (this project):", LabelPosition.TOP)
          .bindText(settings::goal)
          .applyToComponent { rows = 2 }
      }

      row {
        val panel = ToolbarDecorator.createDecorator(familiarityTable)
          .setAddAction { familiarityModel.addRow(FamiliarityEntry()) }
          .setRemoveAction {
            val row = familiarityTable.selectedRow
            if (row >= 0) familiarityModel.removeRow(familiarityTable.convertRowIndexToModel(row))
          }
          .createPanel()
        cell(panel).align(AlignX.FILL)
          .label("Familiarity (self-reported — add whatever's relevant):", LabelPosition.TOP)
      }

      buttonsGroup("Review depth when you hit \"Step Done\":") {
        ReviewLevel.entries.forEach { level -> row { radioButton(level.label, level) } }
      }.bind(settings::reviewLevel)

      row {
        textArea().align(AlignX.FILL)
          .label("Extra context (appended verbatim):", LabelPosition.TOP)
          .bindText(settings::extraContext)
          .applyToComponent { rows = 3 }
      }

      row {
        button("View effective guidance") { showEffectiveGuidance() }
      }
    }

    onReset { familiarityModel.items = settings.familiarity.map { it.copy() } }
    onIsModified { familiarityModel.items != settings.familiarity }
    onApply { settings.familiarity = familiarityModel.items.map { it.copy() }.toMutableList() }
  }

  /** Show what the agent actually receives: the base "how avec-moi works" + this project's brief. */
  private fun showEffectiveGuidance() {
    val text = buildString {
      appendLine("=== Tool instructions (sent to the agent on connect) ===")
      appendLine()
      appendLine(AvecMoiMcpService.INSTRUCTIONS)
      appendLine()
      appendLine("=== This project's brief (from get_avecmoi_guidance) ===")
      appendLine()
      appendLine(settings.guidance())
      append("\n(Reflects saved settings — click Apply to include unsaved edits.)")
    }
    GuidancePreviewDialog(project, text).show()
  }

  private class SubjectColumn : ColumnInfo<FamiliarityEntry, String>("Subject") {
    override fun valueOf(item: FamiliarityEntry): String = item.subject
    override fun isCellEditable(item: FamiliarityEntry): Boolean = true
    override fun setValue(item: FamiliarityEntry, value: String) { item.subject = value }
  }

  private class LevelColumn : ColumnInfo<FamiliarityEntry, Familiarity>("Familiarity") {
    override fun valueOf(item: FamiliarityEntry): Familiarity = item.level
    override fun isCellEditable(item: FamiliarityEntry): Boolean = true
    override fun setValue(item: FamiliarityEntry, value: Familiarity) { item.level = value }
    override fun getWidth(table: JTable?): Int = 160
    override fun getEditor(item: FamiliarityEntry): TableCellEditor =
      DefaultCellEditor(ComboBox(Familiarity.entries.toTypedArray()))
  }

  private class GuidancePreviewDialog(project: Project, private val text: String) : DialogWrapper(project) {
    init {
      title = "Avec Moi — Effective Guidance"
      init()
    }

    override fun createCenterPanel(): JComponent {
      val area = JBTextArea(text).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        caretPosition = 0
      }
      return JBScrollPane(area).apply { preferredSize = Dimension(700, 520) }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)
  }
}
