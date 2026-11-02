package app.heckit.ide.t3toolkit.create

import app.heckit.ide.t3toolkit.fluid.Fluid
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import javax.swing.DefaultCellEditor
import javax.swing.JTable
import javax.swing.table.TableCellEditor
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/** Neue Fluid Component (fluid_components): `Name/Name.html`, auf Wunsch mit `Name.fixture.json`. */
class NewComponentAction : DumbAwareAction("Fluid Component", "Fluid component (fluid_components) with fixture", AllIcons.Nodes.Template) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val dir = e.getData(LangDataKeys.IDE_VIEW)?.directories?.firstOrNull()?.virtualFile
        e.presentation.isEnabledAndVisible = e.project != null && dir != null && Fluid.componentsRootOf(e.project!!, dir) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dir = e.getData(LangDataKeys.IDE_VIEW)?.directories?.firstOrNull()?.virtualFile ?: return
        val form = Form()
        if (!FormDialog(project, form, dir).showAndGet()) return
        val generated = generate(form)
        var html: VirtualFile? = null
        WriteCommandAction.runWriteCommandAction(project, "New Fluid Component", null, {
            val target = VfsUtil.createDirectoryIfMissing(dir, form.name.trim())
            html = target.createChildData(this, form.name.trim() + ".html").also { VfsUtilCore.saveText(it, generated.html) }
            generated.fixture?.let { VfsUtilCore.saveText(target.createChildData(this, form.name.trim() + ".fixture.json"), it) }
        })
        html?.let { OpenFileDescriptor(project, it).navigate(true) }
    }

    class Form {
        var name = "MyComponent"
        val params: MutableList<Param> = mutableListOf(Param("title", "string", false))
        var fixture = true
    }

    class Param(var name: String, var type: String, var optional: Boolean)

    class Generated(val html: String, val fixture: String?)

    private class FormDialog(project: Project, private val form: Form, private val dir: VirtualFile) : DialogWrapper(project) {
        private val model = ListTableModel<Param>(
            object : ColumnInfo<Param, String>("Name") {
                override fun valueOf(item: Param) = item.name
                override fun isCellEditable(item: Param) = true
                override fun setValue(item: Param, value: String?) { item.name = value.orEmpty().trim() }
            },
            object : ColumnInfo<Param, String>("Type") {
                override fun valueOf(item: Param) = item.type
                override fun isCellEditable(item: Param) = true
                override fun setValue(item: Param, value: String?) { item.type = value.orEmpty().trim().ifEmpty { "string" } }
                override fun getEditor(item: Param): TableCellEditor {
                    val combo = ComboBox(TYPES.toTypedArray()).apply { isEditable = true }
                    // Frei getippte Typen stehen nur im Editor des Auswahlfelds, nicht in selectedItem
                    return object : DefaultCellEditor(combo) {
                        override fun getCellEditorValue(): Any? = combo.editor.item?.toString() ?: combo.selectedItem
                    }
                }
            },
            object : ColumnInfo<Param, Boolean>("Optional") {
                override fun valueOf(item: Param) = item.optional
                override fun getColumnClass() = Boolean::class.java
                override fun isCellEditable(item: Param) = true
                override fun setValue(item: Param, value: Boolean?) { item.optional = value == true }
                override fun getWidth(table: JTable) = JBUI.scale(80)
            },
        ).apply { items = form.params.map { Param(it.name, it.type, it.optional) } }
        private val table = TableView(model).apply {
            setShowGrid(false)
            visibleRowCount = 6
            surrendersFocusOnKeystroke = true
        }

        init {
            title = "New Fluid Component"
            init()
        }

        override fun createCenterPanel(): JComponent = panel {
            row("Name:") {
                textField().bindText(form::name).columns(COLUMNS_LARGE).focused()
                    .validationOnApply {
                        val name = it.text.trim()
                        when {
                            !NAME.matches(name) -> error("Use UpperCamelCase, e.g. Teaser")
                            dir.findChild(name)?.findChild("$name.html") != null -> error("Component $name already exists here")
                            else -> null
                        }
                    }
            }
            row {
                val decorated = ToolbarDecorator.createDecorator(table)
                    .setAddAction {
                        table.cellEditor?.stopCellEditing()
                        model.addRow(Param("", "string", false))
                        val row = model.rowCount - 1
                        table.selectionModel.setSelectionInterval(row, row)
                        table.editCellAt(row, 0)
                        table.editorComponent?.requestFocusInWindow()
                    }
                    .createPanel()
                cell(decorated).label("Parameters:", LabelPosition.TOP).align(Align.FILL)
                    .comment("Types: string, boolean, integer, float, array or a class such as Image, Typolink, Navigation")
            }.resizableRow()
            row { checkBox("Create fixture (.fixture.json)").bindSelected(form::fixture) }
        }

        override fun doValidate(): ValidationInfo? {
            table.cellEditor?.stopCellEditing()
            val names = model.items.map { it.name }.filter { it.isNotEmpty() }
            names.firstOrNull { !PARAM.matches(it) }?.let { return ValidationInfo("Invalid parameter name: $it", table) }
            names.groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.let { return ValidationInfo("Duplicate parameter: ${it.key}", table) }
            return null
        }

        override fun doOKAction() {
            table.cellEditor?.stopCellEditing()
            form.params.clear()
            form.params += model.items.filter { it.name.isNotEmpty() }
            super.doOKAction()
        }
    }

    companion object {
        private val NAME = Regex("[A-Z][A-Za-z0-9]*")
        private val PARAM = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val TYPES = listOf(
            "string", "boolean", "integer", "float", "array",
            "Image", "Typolink", "Link", "Navigation", "NavigationItem", "Labels", "Slot", "DateTime",
        )

        fun generate(form: Form): Generated {
            val name = form.name.trim()
            val params = form.params
            val css = name.replaceFirstChar(Char::lowercaseChar)
            val html = buildString {
                append("<fc:component>\n")
                params.forEach { p ->
                    append("  <fc:param name=\"${p.name}\" type=\"${p.type}\"").append(if (p.optional) " optional=\"1\"" else "").append(" />\n")
                }
                if (params.isNotEmpty()) append('\n')
                append("  <fc:renderer>\n    <div class=\"$css\">\n")
                params.firstOrNull { it.type == "string" }?.let { append("      {${it.name}}\n") }
                append("    </div>\n  </fc:renderer>\n</fc:component>\n")
            }
            val fixture = if (!form.fixture) null else buildString {
                append("{\n  \"default\": {")
                append(params.joinToString(",") { "\n    \"${it.name}\": ${sample(it)}" })
                append(if (params.isEmpty()) "}\n}\n" else "\n  }\n}\n")
            }
            return Generated(html, fixture)
        }

        private fun sample(p: Param) = when (p.type.lowercase()) {
            "string" -> "\"${p.name.replaceFirstChar(Char::uppercaseChar)}\""
            "bool", "boolean" -> "false"
            "int", "integer", "float", "double" -> "0"
            "array" -> "[]"
            else -> "null"
        }
    }
}
