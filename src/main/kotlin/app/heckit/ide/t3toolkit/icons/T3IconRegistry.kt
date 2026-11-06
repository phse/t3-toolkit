package app.heckit.ide.t3toolkit.icons

import app.heckit.ide.t3toolkit.T3Project
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/** Eine Icon-Registrierung aus `Configuration/Icons.php`. */
data class IconDef(val identifier: String, val file: VirtualFile, val offset: Int, val source: String?)

/** Icons aus `Configuration/Icons.php` aller Extensions und `<core:icon identifier="…"/>` in Fluid. */
object T3IconRegistry {
    /** `'my-icon' => [` … `'source' => 'EXT:…'` */
    private val ENTRY = Regex("""['"]([A-Za-z0-9_.\-]+)['"]\s*=>\s*\[([^\[\]]*)]""")
    private val SOURCE = Regex("""['"]source['"]\s*=>\s*['"]([^'"]+)['"]""")
    /** Gruppe 2: Identifier */
    val USE = Regex("""\bcore:icon\b[^>}]*?\bidentifier\s*[=:]\s*(["'])([^"'{}]*)\1""")

    fun all(project: Project): Map<String, IconDef> = CachedValuesManager.getManager(project).getCachedValue(project) {
        val result = java.util.TreeMap<String, IconDef>()
        T3Project.get(project).extensions.values.forEach { ext ->
            val file = ext.findFileByRelativePath("Configuration/Icons.php") ?: return@forEach
            val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return@forEach
            ENTRY.findAll(text).forEach { m ->
                result.putIfAbsent(m.groupValues[1], IconDef(m.groupValues[1], file, m.groups[1]!!.range.first, SOURCE.find(m.groupValues[2])?.groupValues?.get(1)))
            }
        }
        CachedValueProvider.Result.create(result as Map<String, IconDef>, PsiModificationTracker.MODIFICATION_COUNT)
    }

    /** Identifier-Angabe unter [offset]: (Identifier, Bereich). */
    fun useAt(text: CharSequence, offset: Int): Pair<String, IntRange>? {
        val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        val start = maxOf(0, minOf(lineStart, offset - 300))
        return USE.findAll(text.subSequence(start, lineEnd)).map { m ->
            m.groupValues[2] to (m.groups[2]!!.range.first + start..m.groups[2]!!.range.last + start)
        }.firstOrNull { offset in it.second.first..it.second.last + 1 }
    }
}
