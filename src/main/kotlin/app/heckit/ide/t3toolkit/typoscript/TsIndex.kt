package app.heckit.ide.t3toolkit.typoscript

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import java.io.DataInput
import java.io.DataOutput

/** Art einer TypoScript-Datei - Schlüssel im Index tragen sie als Präfix (`s:page.10`). */
enum class TsKind(val prefix: String) {
    SETUP("s:"), CONSTANTS("c:"), TSCONFIG("t:");

    companion object {
        fun of(path: String): TsKind = when {
            TsFiles.isConstants(path.substringAfterLast('/')) -> CONSTANTS
            TsFiles.isTsConfig(path) -> TSCONFIG
            else -> SETUP
        }
        fun of(file: TsFile): TsKind = of(file.virtualFile?.path ?: file.name)
    }
}

/** Eine Stelle, an der ein Pfad gesetzt wird. [value]: Wert (gekürzt) bzw. absoluter Quellpfad bei Kopie/Referenz. */
data class TsDef(val offset: Int, val op: Int, val value: String) {
    companion object {
        const val ASSIGN = 0
        const val BLOCK = 1
        const val COPY = 2
        const val REFERENCE = 3
        const val UNSET = 4
        const val MODIFY = 5
        const val MULTILINE = 6
    }
}

/** Alle gesetzten Pfade aller TypoScript-Dateien: `s:page.10` → Stellen mit Operator und Wert. */
class TsPathIndex : FileBasedIndexExtension<String, List<TsDef>>() {
    override fun getName(): ID<String, List<TsDef>> = NAME
    override fun getVersion() = 1
    override fun dependsOnFileContent() = true
    override fun getInputFilter() = FileBasedIndex.InputFilter { it.fileType == TsFileType.INSTANCE }
    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<List<TsDef>> = object : DataExternalizer<List<TsDef>> {
        override fun save(out: DataOutput, value: List<TsDef>) {
            out.writeInt(value.size)
            value.forEach { out.writeInt(it.offset); out.writeByte(it.op); out.writeUTF(it.value) }
        }
        override fun read(input: DataInput): List<TsDef> = List(input.readInt()) { TsDef(input.readInt(), input.readByte().toInt(), input.readUTF()) }
    }

    override fun getIndexer(): DataIndexer<String, List<TsDef>, FileContent> = DataIndexer { content ->
        val file = content.psiFile as? TsFile ?: return@DataIndexer emptyMap()
        val prefix = TsKind.of(content.file.path).prefix
        val result = HashMap<String, MutableList<TsDef>>()
        PsiTreeUtil.processElements(file) { e ->
            if (e is TsStatement) {
                val segments = e.fullSegments()
                val target = e.path?.segmentElements?.lastOrNull()
                if (segments.isNotEmpty() && target != null) {
                    val (op, value) = when (e.kind) {
                        TsElements.ASSIGNMENT -> TsDef.ASSIGN to e.valueText.take(MAX_VALUE)
                        TsElements.BLOCK -> TsDef.BLOCK to ""
                        TsElements.COPY -> TsDef.COPY to e.sourcePath?.absoluteSegments().orEmpty().joinToString(".")
                        TsElements.REFERENCE -> TsDef.REFERENCE to e.sourcePath?.absoluteSegments().orEmpty().joinToString(".")
                        TsElements.UNSET -> TsDef.UNSET to ""
                        TsElements.MODIFICATION -> TsDef.MODIFY to e.functionName.orEmpty()
                        TsElements.MULTILINE_ASSIGNMENT -> TsDef.MULTILINE to e.valueText.take(MAX_VALUE)
                        else -> return@processElements true
                    }
                    result.getOrPut(prefix + segments.joinToString(".")) { ArrayList() } += TsDef(target.textRange.startOffset, op, value)
                }
            }
            true
        }
        result
    }

    companion object {
        val NAME: ID<String, List<TsDef>> = ID.create("app.heckit.ide.t3toolkit.TypoScriptPaths")
        private const val MAX_VALUE = 300
    }
}

/** Zugriff auf [TsPathIndex]. */
object TsIndex {
    data class Def(val file: VirtualFile, val def: TsDef)

    fun definitions(project: Project, kind: TsKind, path: String, scope: GlobalSearchScope = GlobalSearchScope.allScope(project)): List<Def> {
        if (DumbService.isDumb(project) || path.isEmpty()) return emptyList()
        val result = ArrayList<Def>()
        FileBasedIndex.getInstance().processValues(TsPathIndex.NAME, kind.prefix + path, null, { file, defs ->
            defs.forEach { result += Def(file, it) }
            true
        }, scope)
        return result
    }

    /** Alle Pfade einer Art (ohne Präfix). Kann veraltete Schlüssel enthalten - vor der Anzeige ggf. per [exists] prüfen. */
    fun allPaths(project: Project, kind: TsKind): List<String> {
        if (DumbService.isDumb(project)) return emptyList()
        val result = ArrayList<String>()
        FileBasedIndex.getInstance().processAllKeys(TsPathIndex.NAME, { key ->
            if (key.startsWith(kind.prefix)) result += key.substring(kind.prefix.length)
            true
        }, project)
        return result
    }

    fun exists(project: Project, kind: TsKind, path: String): Boolean =
        FileBasedIndex.getInstance().getContainingFiles(TsPathIndex.NAME, kind.prefix + path, GlobalSearchScope.allScope(project)).isNotEmpty()

    /** Direkte Kinder von [parent] (Segmente), die im Projekt gesetzt werden. */
    fun children(project: Project, kind: TsKind, parent: List<String>): Set<String> {
        val prefix = if (parent.isEmpty()) "" else parent.joinToString(".") + "."
        val byChild = LinkedHashMap<String, MutableList<String>>()
        for (path in allPaths(project, kind)) {
            if (!path.startsWith(prefix) || path.length == prefix.length) continue
            val child = splitFirst(path.substring(prefix.length))
            if (child.isNotEmpty()) byChild.getOrPut(child) { ArrayList() } += path
        }
        // Kürzeste Pfade zuerst prüfen - meist gibt es den direkten Schlüssel
        return byChild.filter { (_, paths) -> paths.sortedBy { it.length }.any { exists(project, kind, it) } }.keys
    }

    /** Erstes Segment von `a.b\.c.d` (escaped Punkte bleiben Teil des Segments). */
    private fun splitFirst(path: String): String {
        var i = 0
        while (i < path.length) {
            if (path[i] == '\\' && i + 1 < path.length && path[i + 1] == '.') { i += 2; continue }
            if (path[i] == '.') return path.substring(0, i)
            i++
        }
        return path
    }

    /** Sprungziel einer Definition. */
    fun element(project: Project, def: Def): PsiElement? =
        PsiManager.getInstance(project).findFile(def.file)?.findElementAt(def.def.offset)?.parent
}
