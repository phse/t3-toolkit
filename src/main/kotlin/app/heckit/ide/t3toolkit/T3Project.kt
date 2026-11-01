package app.heckit.ide.t3toolkit

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/**
 * Projektweites Wissen über TYPO3-Extensions: Extension-Schlüssel → Ordner, `EXT:`-Pfade,
 * Root-Pfade für Partials/Layouts/Templates aus TypoScript und Fluid-Components-Namespaces.
 */
class T3Project private constructor(
    val extensions: Map<String, VirtualFile>,
    /** Kind ("Partials", "Layouts", "Templates") → zusätzliche Root-Ordner aus TypoScript. */
    val typoScriptRoots: Map<String, List<VirtualFile>>,
    /** PHP-Namespace (ohne führenden Backslash) → Components-Ordner. */
    val componentNamespaces: Map<String, VirtualFile>,
    val typoScriptFiles: List<VirtualFile>,
    /** Globale Fluid-Namespaces aus ext_localconf.php (Präfix → PHP-Namespaces). */
    val fluidNamespaces: Map<String, List<String>> = emptyMap(),
) {
    fun resolveExtPath(path: String): VirtualFile? {
        val m = EXT_PATH.matchEntire(path.trim()) ?: return null
        val root = extensions[m.groupValues[1]] ?: return null
        val rest = m.groupValues[2].trim('/')
        return if (rest.isEmpty()) root else root.findFileByRelativePath(rest)
    }

    /** Extension-Ordner, in dem [file] liegt (der innerste). */
    fun extensionOf(file: VirtualFile): Pair<String, VirtualFile>? =
        extensions.entries
            .filter { VfsUtilCore.isAncestor(it.value, file, false) }
            .maxByOrNull { it.value.path.length }
            ?.toPair()

    /** `EXT:key/…` für [file], falls es in einer Extension liegt. */
    fun extPathOf(file: VirtualFile): String? {
        val (key, root) = extensionOf(file) ?: return null
        val rel = VfsUtilCore.getRelativePath(file, root) ?: return null
        return "EXT:$key/$rel"
    }

    /** Root-Ordner für [kind], die eigene Extension von [context] zuerst. */
    fun roots(kind: String, context: VirtualFile?): List<VirtualFile> {
        val result = LinkedHashSet<VirtualFile>()
        context?.let { extensionOf(it) }?.second?.findFileByRelativePath("Resources/Private/$kind")?.let { result += it }
        typoScriptRoots[kind].orEmpty().forEach { result += it }
        extensions.values.forEach { ext -> ext.findFileByRelativePath("Resources/Private/$kind")?.let { result += it } }
        return result.toList()
    }

    companion object {
        private val EXT_PATH = Regex("""EXT:([A-Za-z0-9_]+)(/.*)?""")
        private val COMPOSER_KEY = Regex(""""extension-key"\s*:\s*"([A-Za-z0-9_]+)"""")
        private val TS_ROOT = Regex("""EXT:[A-Za-z0-9_]+/[^\s"'{}]*?/(Partials|Layouts|Templates)/?(?=[\s"']|$)""", RegexOption.MULTILINE)
        private val FC_NAMESPACE = Regex(
            """\['fluid_components']\s*\[\s*'namespaces'\s*]\s*\[\s*['"]([^'"]+)['"]\s*]\s*=\s*([^;]+);""",
        )
        private val EXT_PATH_CALL = Regex(
            """extPath\(\s*['"]([A-Za-z0-9_]+)['"]\s*(?:,\s*['"]([^'"]*)['"]\s*)?\)(?:\s*\.\s*['"]([^'"]*)['"])?""",
        )
        private val EXT_STRING = Regex("""['"](EXT:[^'"]+)['"]""")
        private val SYS_NS = Regex("""\['fluid']\s*\[\s*'namespaces'\s*]\s*\[\s*['"]([A-Za-z0-9_]+)['"]\s*](\s*\[\s*])?\s*=\s*([^;]+);""")
        private val PHP_STRING = Regex("""['"]([A-Za-z0-9_\\\\]+)['"]""")
        /** Vorgaben aus typo3/cms-core DefaultConfiguration.php */
        val DEFAULT_FLUID_NAMESPACES = mapOf(
            "f" to listOf("TYPO3Fluid\\Fluid\\ViewHelpers", "TYPO3\\CMS\\Fluid\\ViewHelpers"),
            "core" to listOf("TYPO3\\CMS\\Core\\ViewHelpers"),
        )
        val TS_EXTENSIONS = setOf("typoscript", "tsconfig", "tss", "t3s")

        fun get(project: Project): T3Project = CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(build(project), PsiModificationTracker.MODIFICATION_COUNT)
        }

        private fun build(project: Project): T3Project {
            val scope = GlobalSearchScope.allScope(project)
            val extensions = LinkedHashMap<String, VirtualFile>()
            fun add(key: String, dir: VirtualFile) {
                val existing = extensions[key]
                // Echte Pakete vor Kopien/Symlinks unter vendor bzw. typo3conf/ext bevorzugen
                if (existing == null || ("/vendor/" in existing.path && "/vendor/" !in dir.path)) extensions[key] = dir
            }
            FilenameIndex.getVirtualFilesByName("composer.json", scope).forEach { file ->
                val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return@forEach
                if ("typo3-cms-extension" !in text && "extension-key" !in text) return@forEach
                val key = COMPOSER_KEY.find(text)?.groupValues?.get(1) ?: return@forEach
                file.parent?.let { add(key, it) }
            }
            FilenameIndex.getVirtualFilesByName("ext_emconf.php", scope).forEach { file ->
                val dir = file.parent ?: return@forEach
                if (dir.name !in extensions) add(dir.name, dir)
            }

            val tsFiles = buildList {
                TS_EXTENSIONS.forEach { addAll(FilenameIndex.getAllFilesByExt(project, it, scope)) }
                listOf("setup.txt", "constants.txt", "setup.ts", "constants.ts").forEach {
                    addAll(FilenameIndex.getVirtualFilesByName(it, scope))
                }
            }.distinct()

            val tmp = T3Project(extensions, emptyMap(), emptyMap(), tsFiles)
            val roots = HashMap<String, LinkedHashSet<VirtualFile>>()
            val texts = tsFiles.mapNotNull { f -> runCatching { VfsUtilCore.loadText(f) }.getOrNull() }
            fun addRoot(kind: String, path: String) {
                val dir = tmp.resolveExtPath(path.trim().trimEnd('/'))
                if (dir != null && dir.isDirectory) roots.getOrPut(kind) { LinkedHashSet() } += dir
            }
            // 1. Pfade, die schon so heißen (…/Partials/ usw.) - auch wenn sie nur als Konstante vorkommen
            texts.forEach { text -> TS_ROOT.findAll(text).forEach { m -> addRoot(m.groupValues[1], m.value) } }
            // 2. Alles unter …RootPaths bzw. …RootPath, mit aufgelösten {${'$'}Konstanten} (z. B. Structures als Partial-Ordner)
            val assignments = texts.flatMap { TypoScriptLite.assignments(it) }
            val constants = assignments.associate { it.first to it.second }
            for ((key, raw) in assignments) {
                val last = key.split('.').dropLast(1).lastOrNull { it.endsWith("RootPaths", true) || it.endsWith("RootPath", true) }
                    ?: key.substringAfterLast('.').takeIf { it.endsWith("RootPath", true) } ?: continue
                val kind = when {
                    last.startsWith("partial", true) -> "Partials"
                    last.startsWith("layout", true) -> "Layouts"
                    last.startsWith("template", true) -> "Templates"
                    else -> continue
                }
                val value = TypoScriptLite.CONSTANT.replace(raw) { constants[it.groupValues[1]] ?: it.value }
                if (value.startsWith("EXT:")) addRoot(kind, value)
            }

            val namespaces = LinkedHashMap<String, VirtualFile>()
            val fluidNs = LinkedHashMap<String, MutableList<String>>()
            FilenameIndex.getVirtualFilesByName("ext_localconf.php", scope).forEach { file ->
                val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return@forEach
                SYS_NS.findAll(text).forEach { m ->
                    val values = PHP_STRING.findAll(m.groupValues[3]).map { it.groupValues[1].replace("\\\\", "\\").trim('\\') }.toList()
                    val list = fluidNs.getOrPut(m.groupValues[1]) { ArrayList() }
                    if (m.groupValues[2].isEmpty()) list.clear()
                    values.forEach { if (it !in list) list += it }
                }
                if ("fluid_components" !in text) return@forEach
                FC_NAMESPACE.findAll(text).forEach { m ->
                    val ns = m.groupValues[1].replace("\\\\", "\\").trim('\\')
                    val value = m.groupValues[2]
                    val dir = EXT_PATH_CALL.find(value)?.let { c ->
                        val root = extensions[c.groupValues[1]]
                        val rel = (c.groupValues[2] + c.groupValues[3]).trim('/')
                        if (rel.isEmpty()) root else root?.findFileByRelativePath(rel)
                    } ?: EXT_STRING.find(value)?.let { tmp.resolveExtPath(it.groupValues[1]) }
                    if (dir != null) namespaces[ns] = dir
                }
            }
            return T3Project(extensions, roots.mapValues { it.value.toList() }, namespaces, tsFiles, fluidNs)
        }
    }
}
