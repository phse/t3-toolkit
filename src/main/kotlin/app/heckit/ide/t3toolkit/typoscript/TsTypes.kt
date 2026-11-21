package app.heckit.ide.t3toolkit.typoscript

import com.intellij.openapi.project.Project
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import java.util.concurrent.ConcurrentHashMap

/**
 * Typ eines TypoScript-Objekts: ausdrücklich zugewiesen (`page.10 = FLUIDTEMPLATE`), übernommen per Kopie/Referenz
 * (`lib.a < lib.b`), Datenprozessor nach Alias/Klasse, sonst aus der Eigenschaft des Elternobjekts (`page.config` → CONFIG).
 */
object TsTypes {
    private const val NONE = "\u0000"
    private val UPPER = Regex("[A-Z][A-Z0-9_]+")

    private fun cache(project: Project): MutableMap<String, String> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(ConcurrentHashMap<String, String>(), PsiModificationTracker.MODIFICATION_COUNT)
        }

    fun typeOf(project: Project, segments: List<String>, kind: TsKind): String? = typeOf(project, segments, kind, HashSet())

    private fun typeOf(project: Project, segments: List<String>, kind: TsKind, visiting: MutableSet<String>): String? {
        if (kind == TsKind.CONSTANTS) return null
        if (segments.isEmpty()) return TsRef.rootType(kind == TsKind.TSCONFIG)
        val path = segments.joinToString(".")
        val key = kind.prefix + path
        cache(project)[key]?.let { return it.takeIf { t -> t != NONE } }
        if (!visiting.add(key) || visiting.size > 12) return null
        val result = explicitType(project, segments, kind, visiting) ?: structuralType(project, segments, kind, visiting)
        visiting.remove(key)
        cache(project)[key] = result ?: NONE
        return result
    }

    private fun explicitType(project: Project, segments: List<String>, kind: TsKind, visiting: MutableSet<String>): String? {
        val path = segments.joinToString(".")
        val defs = TsIndex.definitions(project, kind, path)
        var copied: String? = null
        var processor: String? = null
        for (d in defs) {
            when (d.def.op) {
                TsDef.ASSIGN -> {
                    val v = d.def.value.trim()
                    if (UPPER.matches(v)) return v
                    if (segments.size >= 2 && segments[segments.size - 2] == "dataProcessing" && v.isNotEmpty()) {
                        // Bekannter Prozessor schlägt unbekannte Klassen bzw. halb getippte Werte
                        val known = processorAlias(v)?.let { "DP:$it" }?.takeIf { it in TsRef.types }
                        if (known != null) processor = known else if (processor == null) processor = "DATA_PROCESSOR"
                    }
                }
                TsDef.COPY, TsDef.REFERENCE -> if (copied == null && d.def.value.isNotEmpty()) {
                    copied = typeOf(project, split(d.def.value), kind, visiting)
                }
            }
        }
        return processor ?: copied
    }

    private fun structuralType(project: Project, segments: List<String>, kind: TsKind, visiting: MutableSet<String>): String? {
        val parent = typeOf(project, segments.dropLast(1), kind, visiting) ?: return null
        return TsRef.prop(parent, segments.last())?.childType
    }

    /** Eigenschaft [name] unter dem Objekt [parent], falls bekannt. */
    fun propOf(project: Project, parent: List<String>, name: String, kind: TsKind): TsRef.Prop? =
        TsRef.prop(typeOf(project, parent, kind), name)

    fun isKnownProperty(project: Project, parent: List<String>, name: String, file: TsFile?): Boolean {
        val kind = file?.let { TsKind.of(it) } ?: TsKind.SETUP
        val prop = propOf(project, parent, name, kind) ?: return false
        return prop.name == name
    }

    /** Alias eines Datenprozessors (`menu`) aus Alias oder Klassenname. */
    fun processorAlias(value: String): String? {
        val v = value.trim().trimStart('\\')
        if (v in TsRef.dataProcessors) return v
        return TsRef.dataProcessors.entries.firstOrNull { it.value == v }?.key
    }

    /** `a.b\.c.d` → [a, b\.c, d] */
    fun split(path: String): List<String> {
        val result = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < path.length) {
            val c = path[i]
            if (c == '\\' && i + 1 < path.length && path[i + 1] == '.') { sb.append("\\."); i += 2; continue }
            if (c == '.') { result += sb.toString(); sb.clear() } else sb.append(c)
            i++
        }
        result += sb.toString()
        return result.filter { it.isNotEmpty() }
    }
}
