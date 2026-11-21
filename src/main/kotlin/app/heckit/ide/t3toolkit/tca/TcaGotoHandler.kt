package app.heckit.ide.t3toolkit.tca

import app.heckit.ide.t3toolkit.NavTarget
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression

/**
 * Strg/⌘-Klick in der TCA: `foreign_table`/`allowed`/`MM` zur Tabelle, `foreign_field` & Co. zum Feld der Fremdtabelle,
 * Felder in `ctrl` (label, sortby …) und `showitem` (Felder und `--palette--;;name`) zu ihrer Definition.
 */
class TcaGotoHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(source: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val literal = PsiTreeUtil.getParentOfType(source, StringLiteralExpression::class.java, false) ?: return null
        val pos = TcaPosition.of(literal) ?: return null
        val key = pos.valueOf ?: return null
        val project = literal.project
        val path = pos.context.path
        val table = pos.context.table
        val contents = literal.contents
        val inValue = offset - literal.textRange.startOffset - literal.valueRange.startOffset
        // Element einer Komma-Liste unter dem Cursor
        val itemStart = contents.lastIndexOf(',', (inValue - 1).coerceAtLeast(0)).let { if (it < 0 || it >= inValue) 0 else it + 1 }
        val itemEnd = contents.indexOf(',', inValue).let { if (it < 0) contents.length else it }
        val item = if (inValue in 0..contents.length) contents.substring(itemStart.coerceAtMost(itemEnd), itemEnd).trim() else contents.trim()
        val inConfig = path.lastOrNull() == "config"

        val targets: List<PsiElement> = when {
            key in setOf("foreign_table", "allowed") && inConfig -> tableTargets(project, item)
            key == "MM" && inConfig -> listOfNotNull(Tca.sqlTables(project)[item])
            key in setOf("foreign_field", "foreign_sortby", "foreign_label", "foreign_table_field", "symmetric_field") && inConfig ->
                pos.sibling("foreign_table")?.let { listOfNotNull(Tca.columns(project, it)[item]) }.orEmpty()
            path == listOf("ctrl") && key in TcaData.ctrlFieldKeys && table != null -> listOfNotNull(Tca.columns(project, table)[item])
            path == listOf("ctrl", "enablecolumns") && table != null -> listOfNotNull(Tca.columns(project, table)[item])
            key == "showitem" && table != null -> {
                val parts = item.split(';')
                when {
                    parts[0] == "--palette--" && parts.size >= 3 -> listOfNotNull(Tca.palettes(project, table)[parts[2].trim()])
                    parts[0].startsWith("--") -> emptyList()
                    else -> listOfNotNull(Tca.columns(project, table)[parts[0].trim()])
                }
            }
            else -> emptyList()
        }
        return targets.map { if (it is StringLiteralExpression) NavTarget.of(it) else it }.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private fun tableTargets(project: com.intellij.openapi.project.Project, name: String): List<PsiElement> =
        listOfNotNull(Tca.tcaFileOf(project, name) ?: Tca.sqlTables(project)[name])
}
