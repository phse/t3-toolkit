package app.heckit.ide.t3toolkit.tca

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.ArrayCreationExpression
import com.jetbrains.php.lang.psi.elements.ArrayHashElement
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression

/** Position eines Strings in einem TCA-Array: als Schlüssel (bzw. neuer Eintrag) oder als Wert eines Schlüssels. */
internal data class TcaPosition(val array: ArrayCreationExpression?, val context: TcaContext, val valueOf: String?) {
    /** Wert des Schlüssels [key] im selben Array (z. B. `type`). */
    fun sibling(key: String): String? = array?.let { PsiTreeUtil.getChildrenOfTypeAsList(it, ArrayHashElement::class.java) }.orEmpty()
        .firstOrNull { (it.key as? StringLiteralExpression)?.contents == key }?.let { (it.value as? StringLiteralExpression)?.contents }

    val presentKeys: Set<String> get() = array?.let { PsiTreeUtil.getChildrenOfTypeAsList(it, ArrayHashElement::class.java) }.orEmpty()
        .mapNotNull { (it.key as? StringLiteralExpression)?.contents }.toSet()

    companion object {
        fun of(literal: StringLiteralExpression): TcaPosition? {
            if (!Tca.inTca(literal)) return null
            val wrapper = literal.parent ?: return null
            // $GLOBALS['TCA']['t']['types']['0']['showitem'] = '…'
            if (wrapper is com.jetbrains.php.lang.psi.elements.AssignmentExpression && wrapper.value == literal) {
                val (table, keys) = Tca.globalsPath(wrapper.variable) ?: return null
                if (keys.isEmpty()) return null
                return TcaPosition(null, TcaContext(table, keys.dropLast(1)), keys.last())
            }
            val hash = wrapper.parent as? ArrayHashElement
            val array: ArrayCreationExpression
            val valueOf: String?
            when {
                hash != null && hash.key == literal -> { array = hash.parent as? ArrayCreationExpression ?: return null; valueOf = null }
                hash != null && hash.value == literal -> {
                    array = hash.parent as? ArrayCreationExpression ?: return null
                    valueOf = (hash.key as? StringLiteralExpression)?.contents ?: return null
                }
                wrapper.parent is ArrayCreationExpression -> { array = wrapper.parent as ArrayCreationExpression; valueOf = null }
                else -> return null
            }
            val ctx = Tca.contextOf(array) ?: return null
            return TcaPosition(array, ctx, valueOf)
        }
    }
}

/** Was an einer TCA-Stelle erwartet wird. */
internal object TcaSchema {
    /** Schlüssel für das Array unter [path]; [type] = config.type im selben Array. */
    fun keysFor(path: List<String>, type: String?): Map<String, String>? {
        val n = path.size
        return when {
            n == 0 -> TcaData.tableKeys
            path == listOf("ctrl") -> TcaData.ctrlKeys
            path == listOf("ctrl", "enablecolumns") -> TcaData.enableColumnsKeys
            n == 2 && path[0] == "columns" -> TcaData.columnKeys
            n == 3 && path[0] == "columns" && path[2] == "config" -> configKeys(type)
            n == 2 && path[0] == "types" -> TcaData.typeKeys
            n == 2 && path[0] == "palettes" -> TcaData.paletteKeys
            n >= 2 && path[n - 2] == "items" && path[n - 1] == "#" -> TcaData.itemKeys
            // columnsOverrides.feld.config
            n == 5 && path[0] == "types" && path[2] == "columnsOverrides" && path[4] == "config" -> configKeys(type)
            n == 4 && path[0] == "types" && path[2] == "columnsOverrides" -> TcaData.columnKeys
            else -> null
        }
    }

    private fun configKeys(type: String?): Map<String, String> {
        val keys = type?.let { TcaData.configKeys[it] } ?: TcaData.allConfigKeys
        return keys.associateWith { if (it == "type") "Field type" else "" }
    }
}

class TcaCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC) return
        val literal = PsiTreeUtil.getParentOfType(parameters.position, StringLiteralExpression::class.java, false) ?: return
        val pos = TcaPosition.of(literal) ?: return
        val project = literal.project
        val typedFull = literal.contents.substringBefore(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED)
        val path = pos.context.path
        val table = pos.context.table

        if (pos.valueOf == null) {
            val keys = TcaSchema.keysFor(path, pos.sibling("type")) ?: return
            val present = pos.presentKeys
            val matcher = result.withPrefixMatcher(typedFull)
            keys.forEach { (k, desc) ->
                if (k in present) return@forEach
                matcher.addElement(LookupElementBuilder.create(k).withIcon(AllIcons.Nodes.Property).withTailText(if (desc.isEmpty()) null else "  $desc", true))
            }
            matcher.stopHere()
            return
        }

        val key = pos.valueOf
        val inConfig = path.lastOrNull() == "config"
        val values: List<Pair<String, String?>> = when {
            key == "type" && inConfig -> TcaData.types.map { it.key to it.value }
            key == "renderType" && inConfig -> TcaData.renderTypes[pos.sibling("type")].orEmpty().map { it to null }
            key == "format" && inConfig -> TcaData.formatByType[pos.sibling("type")].orEmpty().map { it to null }
            key in setOf("foreign_table", "allowed") && inConfig && pos.sibling("type") != "file" ->
                listOptions(typedFull, key == "allowed", Tca.tables(project).keys.map { it to "table" })
            key == "MM" && inConfig -> Tca.sqlTables(project).keys.filter { it.endsWith("_mm") || "_mm_" in it }.map { it to "MM table" }
            key == "eval" -> listOptions(typedFull, true, TcaData.evalValues.map { it to null })
            key in TcaData.values -> TcaData.values[key].orEmpty().map { it to null }
            key in setOf("foreign_field", "foreign_sortby", "foreign_label", "foreign_table_field", "symmetric_field") && inConfig -> {
                val foreign = pos.sibling("foreign_table")
                foreign?.let { Tca.columns(project, it).keys.map { c -> c to foreign } }.orEmpty()
            }
            path == listOf("ctrl") && key in TcaData.ctrlFieldKeys && table != null ->
                listOptions(typedFull, key in setOf("label_alt", "searchFields"), Tca.columns(project, table).keys.map { it to table })
            path == listOf("ctrl", "enablecolumns") && table != null -> Tca.columns(project, table).keys.map { it to table }
            key == "showitem" && table != null -> {
                showitemCompletion(project, table, typedFull, result)
                return
            }
            else -> return
        }
        if (values.isEmpty()) return
        val prefix = if (key in setOf("eval", "allowed", "label_alt", "searchFields")) typedFull.substringAfterLast(',').trimStart() else typedFull
        val matcher = result.withPrefixMatcher(prefix)
        values.forEach { (v, type) -> matcher.addElement(LookupElementBuilder.create(v).withIcon(AllIcons.Nodes.Enum).withTypeText(type)) }
        matcher.stopHere()
    }

    /** Bei Listen (`trim,int`) nur das letzte Element ersetzen und schon gewählte weglassen. */
    private fun listOptions(typed: String, list: Boolean, options: List<Pair<String, String?>>): List<Pair<String, String?>> {
        if (!list) return options
        val chosen = typed.split(',').dropLast(1).map { it.trim() }.toSet()
        return options.filter { it.first !in chosen }
    }

    private fun showitemCompletion(project: Project, table: String, typed: String, result: CompletionResultSet) {
        val item = typed.substringAfterLast(',').trimStart()
        if (item.startsWith("--palette--;")) {
            // --palette--;Label;name
            val parts = item.split(';')
            if (parts.size >= 3) {
                val matcher = result.withPrefixMatcher(parts.drop(2).joinToString(";"))
                Tca.palettes(project, table).keys.forEach { matcher.addElement(LookupElementBuilder.create(it).withIcon(AllIcons.Nodes.Folder).withTypeText("palette")) }
                matcher.stopHere()
            }
            return
        }
        if (item.startsWith("--div--") || ';' in item) return
        val matcher = result.withPrefixMatcher(item)
        Tca.columns(project, table).keys.forEach { matcher.addElement(LookupElementBuilder.create(it).withIcon(AllIcons.Nodes.Field).withTypeText(table)) }
        matcher.addElement(LookupElementBuilder.create("--palette--;;").withIcon(AllIcons.Nodes.Folder).withTailText("name", true).withTypeText("palette"))
        matcher.addElement(LookupElementBuilder.create("--div--;").withIcon(AllIcons.Nodes.Folder).withTailText("Label", true).withTypeText("tab"))
        matcher.addElement(LookupElementBuilder.create("--linebreak--").withIcon(AllIcons.Actions.SplitHorizontally).withTypeText("in palettes"))
        matcher.stopHere()
    }
}
