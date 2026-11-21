package app.heckit.ide.t3toolkit.tca

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.ArrayAccessExpression
import com.jetbrains.php.lang.psi.elements.ArrayCreationExpression
import com.jetbrains.php.lang.psi.elements.ArrayHashElement
import com.jetbrains.php.lang.psi.elements.AssignmentExpression
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.ParameterList
import com.jetbrains.php.lang.psi.elements.PhpReturn
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import com.jetbrains.php.lang.psi.elements.Variable

/**
 * Wo in der TCA ein Array liegt: Tabelle und Schlüsselpfad (`columns`, `title`, `config`). Erkannt werden
 * `return [...]` in `Configuration/TCA/<tabelle>.php`, `$GLOBALS['TCA']['tabelle'][…] = [...]` und `addTCAcolumns('tabelle', [...])`
 * (auch über eine Variable).
 */
data class TcaContext(val table: String?, val path: List<String>)

object Tca {
    /** Liegt [file] unter `Configuration/TCA`? */
    fun isTcaFile(file: VirtualFile?): Boolean = file != null && "/Configuration/TCA/" in file.path && file.extension == "php"

    /** Kontext des Arrays [array] (Pfad bis einschließlich dieses Arrays); Listeneinträge ohne Schlüssel als `#`. */
    fun contextOf(array: ArrayCreationExpression): TcaContext? {
        val keys = ArrayList<String>()
        var current: PsiElement = array
        while (true) {
            val wrapper = current.parent ?: return null
            val hash = wrapper.parent as? ArrayHashElement
            when {
                // Wert eines Schlüssels: ['config' => [ … ]]
                hash != null && hash.value == current -> {
                    keys.add(0, keyText(hash) ?: return null)
                    current = hash.parent as? ArrayCreationExpression ?: return null
                }
                // Listeneintrag: ['items' => [[ … ], [ … ]]]
                wrapper.parent is ArrayCreationExpression && wrapper !is ArrayHashElement -> {
                    keys.add(0, "#")
                    current = wrapper.parent
                }
                else -> break
            }
        }
        val root = current as? ArrayCreationExpression ?: return null
        val file = root.containingFile?.originalFile?.virtualFile ?: root.containingFile?.virtualFile
        val (table, prefix) = rootContext(root, file) ?: return null
        return TcaContext(table, prefix + keys)
    }

    private fun keyText(hash: ArrayHashElement): String? = (hash.key as? StringLiteralExpression)?.contents ?: hash.key?.text

    private fun rootContext(root: ArrayCreationExpression, file: VirtualFile?): Pair<String?, List<String>>? {
        val parent = root.parent
        // return [...] in Configuration/TCA/tabelle.php
        if (parent is PhpReturn && isTcaFile(file) && file?.parent?.name == "TCA") return file.nameWithoutExtension to emptyList()
        // $GLOBALS['TCA']['tabelle']['columns'] = [...]
        if (parent is AssignmentExpression && parent.value == root) globalsPath(parent.variable)?.let { return it }
        // addTCAcolumns('tabelle', [...])
        if (parent is ParameterList) columnsCall(parent)?.let { return it to listOf("columns") }
        // $tempColumns = [...]; …; addTCAcolumns('tabelle', $tempColumns)
        if (parent is AssignmentExpression && parent.value == root) {
            val variable = parent.variable as? Variable ?: return null
            val name = variable.name
            val call = PsiTreeUtil.findChildrenOfType(root.containingFile, MethodReference::class.java).firstOrNull { c ->
                c.name == "addTCAcolumns" && (c.parameters.getOrNull(1) as? Variable)?.name == name
            }
            if (call != null) return ((call.parameters.firstOrNull() as? StringLiteralExpression)?.contents) to listOf("columns")
            // Überschreibungen ohne erkennbaren Zielort: Tabelle aus dem Dateinamen
            if (isTcaFile(file) && name.contains("olumns")) return file?.nameWithoutExtension to listOf("columns")
        }
        return null
    }

    private fun columnsCall(params: ParameterList): String? {
        val call = params.parent as? MethodReference ?: return null
        if (call.name != "addTCAcolumns") return null
        return (call.parameters.firstOrNull() as? StringLiteralExpression)?.contents
    }

    /** `$GLOBALS['TCA']['tt_content']['columns']['x']` → (tt_content, [columns, x]). */
    fun globalsPath(expr: PsiElement?): Pair<String?, List<String>>? {
        val keys = ArrayList<String>()
        var e = expr
        while (e is ArrayAccessExpression) {
            val key = (e.index?.value as? StringLiteralExpression)?.contents ?: return null
            keys.add(0, key)
            e = e.value
        }
        if ((e as? Variable)?.name != "GLOBALS" || keys.firstOrNull() != "TCA" || keys.size < 2) return null
        return keys[1] to keys.drop(2)
    }

    // --- Tabellen und Felder ---

    data class Table(val name: String, val files: List<VirtualFile>)

    /** Alle Tabellen mit TCA-Datei (`Configuration/TCA/<tabelle>.php`). */
    fun tables(project: Project): Map<String, Table> = CachedValuesManager.getManager(project).getCachedValue(project) {
        val result = LinkedHashMap<String, MutableList<VirtualFile>>()
        FilenameIndex.getAllFilesByExt(project, "php", GlobalSearchScope.allScope(project)).forEach { f ->
            if (f.parent?.name == "TCA" && f.parent?.parent?.name == "Configuration") result.getOrPut(f.nameWithoutExtension) { ArrayList() } += f
        }
        CachedValueProvider.Result.create(result.mapValues { Table(it.key, it.value) }, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
    }

    /** Tabellen aus `ext_tables.sql` (`CREATE TABLE name`) samt Stelle. */
    fun sqlTables(project: Project): Map<String, PsiElement> = CachedValuesManager.getManager(project).getCachedValue(project) {
        val result = LinkedHashMap<String, PsiElement>()
        val psi = PsiManager.getInstance(project)
        FilenameIndex.getVirtualFilesByName("ext_tables.sql", GlobalSearchScope.allScope(project)).forEach { vf ->
            val file = psi.findFile(vf) ?: return@forEach
            CREATE_TABLE.findAll(file.viewProvider.contents).forEach { m ->
                file.findElementAt(m.range.first + m.value.indexOf(m.groupValues[1]))?.let { result.putIfAbsent(m.groupValues[1], it) }
            }
        }
        CachedValueProvider.Result.create(result, PsiModificationTracker.MODIFICATION_COUNT)
    }

    private val CREATE_TABLE = Regex("""(?i)CREATE\s+TABLE\s+`?([A-Za-z0-9_]+)`?""")

    /** Felder bzw. Paletten einer Tabelle: Name → Schlüssel-Element (aus TCA-Datei und Overrides). */
    fun columns(project: Project, table: String): Map<String, PsiElement> = members(project, table, "columns")
    fun palettes(project: Project, table: String): Map<String, PsiElement> = members(project, table, "palettes")

    private fun members(project: Project, table: String, section: String): Map<String, PsiElement> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(HashMap<String, Map<String, PsiElement>>(), PsiModificationTracker.MODIFICATION_COUNT)
        }.getOrPut("$table/$section") { collectMembers(project, table, section) }

    private fun collectMembers(project: Project, table: String, section: String): Map<String, PsiElement> {
        val result = LinkedHashMap<String, PsiElement>()
        val psi = PsiManager.getInstance(project)
        val files = FilenameIndex.getVirtualFilesByName("$table.php", GlobalSearchScope.allScope(project)).filter { isTcaFile(it) }
            .sortedBy { if (it.parent?.name == "TCA") 0 else 1 }
        val others = FilenameIndex.getAllFilesByExt(project, "php", GlobalSearchScope.allScope(project))
            .filter { it.parent?.name == "Overrides" && isTcaFile(it) && it !in files }
        for (vf in files + others) {
            val file = psi.findFile(vf) ?: continue
            if (vf in others && table !in file.viewProvider.contents) continue
            collectFrom(file, table, section, result)
        }
        return result
    }

    private fun collectFrom(file: PsiFile, table: String, section: String, into: MutableMap<String, PsiElement>) {
        PsiTreeUtil.findChildrenOfType(file, ArrayCreationExpression::class.java).forEach { arr ->
            val ctx = contextOf(arr) ?: return@forEach
            if (ctx.table != table || ctx.path != listOf(section)) return@forEach
            PsiTreeUtil.getChildrenOfTypeAsList(arr, ArrayHashElement::class.java).forEach { el ->
                val key = el.key as? StringLiteralExpression ?: return@forEach
                into.putIfAbsent(key.contents, key)
            }
        }
        // $GLOBALS['TCA']['t']['columns']['x'] = [...]
        PsiTreeUtil.findChildrenOfType(file, AssignmentExpression::class.java).forEach { a ->
            val p = globalsPath(a.variable) ?: return@forEach
            if (p.first == table && p.second.size == 2 && p.second[0] == section) {
                val keyEl = (a.variable as? ArrayAccessExpression)?.index?.value ?: return@forEach
                into.putIfAbsent(p.second[1], keyEl)
            }
        }
    }

    fun isTableInProject(project: Project, name: String) = name in tables(project) || name in sqlTables(project)

    fun tcaFileOf(project: Project, table: String): PsiFile? =
        tables(project)[table]?.files?.firstNotNullOfOrNull { PsiManager.getInstance(project).findFile(it) }

    fun inTca(element: PsiElement): Boolean {
        val vf = element.containingFile?.originalFile?.virtualFile ?: element.containingFile?.virtualFile
        return isTcaFile(vf) || "\$GLOBALS['TCA']" in element.containingFile.viewProvider.contents
    }

    fun relative(file: VirtualFile, root: VirtualFile) = VfsUtilCore.getRelativePath(file, root)
}
