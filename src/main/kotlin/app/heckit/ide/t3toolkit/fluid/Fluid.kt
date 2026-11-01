package app.heckit.ide.t3toolkit.fluid

import app.heckit.ide.t3toolkit.T3Project
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

data class ComponentParam(val name: String, val type: String?, val optional: Boolean, val default: String?, val tag: XmlTag)

/** Was eine Fluid-Datei für TYPO3 ist. */
data class FluidRole(val kind: String, val name: String)

object Fluid {
    val PARTIAL = Regex("""\bpartial\s*[=:]\s*(["'])([^"'{}]+)\1""")
    val SECTION = Regex("""\bsection\s*[=:]\s*(["'])([^"'{}]+)\1""")
    val LAYOUT = Regex("""\bf:layout\b[^>}]*?\bname\s*[=:]\s*(["'])([^"'{}]+)\1""")
    val EXT_PATH = Regex("""EXT:[A-Za-z0-9_]+/[^\s"'<>(){},]*""")
    val COMPONENT_TAG = Regex("""[<{]/?([A-Za-z0-9_]+):([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*)""")
    private val NS_DECL = Regex("""\{namespace\s+([A-Za-z0-9_]+)\s*=\s*([A-Za-z0-9_\\]+)\s*}""")
    private val XMLNS = Regex("""xmlns:([A-Za-z0-9_]+)\s*=\s*["']https?://typo3\.org/ns/([^"']+)["']""")

    /** Präfix → PHP-Namespace aus `{namespace …}` und `xmlns:…`. */
    fun namespaces(text: CharSequence): Map<String, String> = buildMap {
        NS_DECL.findAll(text).forEach { put(it.groupValues[1], it.groupValues[2].trim('\\')) }
        XMLNS.findAll(text).forEach { put(it.groupValues[1], it.groupValues[2].replace('/', '\\').trim('\\')) }
    }

    fun resolvePartial(project: Project, context: VirtualFile?, name: String): List<VirtualFile> =
        resolveInRoots(project, "Partials", context, name)

    fun resolveLayout(project: Project, context: VirtualFile?, name: String): List<VirtualFile> =
        resolveInRoots(project, "Layouts", context, name)

    private fun resolveInRoots(project: Project, kind: String, context: VirtualFile?, name: String): List<VirtualFile> {
        val rel = name.trim('/').let { if (it.endsWith(".html")) it else "$it.html" }
        return T3Project.get(project).roots(kind, context).mapNotNull { it.findFileByRelativePath(rel) }.distinct()
    }

    /** Components-Ordner und Restsegmente zu einem PHP-Namespace. */
    private fun componentBase(project: Project, phpNs: String): Pair<VirtualFile, List<String>>? {
        val t3 = T3Project.get(project)
        val registered = t3.componentNamespaces.entries
            .filter { phpNs == it.key || phpNs.startsWith(it.key + "\\") }
            .maxByOrNull { it.key.length }
        if (registered != null) {
            return registered.value to phpNs.removePrefix(registered.key).split('\\').filter { it.isNotEmpty() }
        }
        // Rückfall nach Konvention: Vendor\ExtName\Components\… → EXT:ext_name/Resources/Private/Components
        val parts = phpNs.split('\\')
        val idx = parts.indexOf("Components")
        if (idx < 2) return null
        val key = parts[idx - 1].replace(Regex("(?<=[a-z0-9])([A-Z])"), "_$1").lowercase()
        val dir = t3.extensions[key]?.findFileByRelativePath("Resources/Private/Components") ?: return null
        return dir to parts.drop(idx + 1)
    }

    fun resolveComponent(project: Project, phpNs: String, localName: String): VirtualFile? {
        val (base, rest) = componentBase(project, phpNs) ?: return null
        val segments = rest + localName.split('.').map { it.replaceFirstChar(Char::uppercaseChar) }
        val dir = base.findFileByRelativePath(segments.joinToString("/")) ?: return null
        return dir.findChild(segments.last() + ".html")
    }

    fun resolveComponentTag(file: PsiFile, tagName: String): VirtualFile? {
        val prefix = tagName.substringBefore(':', "")
        if (prefix.isEmpty() || prefix == "f" || prefix == "fc") return null
        val namespaces = ViewHelpers.namespacesFor(file)[prefix] ?: return null
        return namespaces.asReversed().firstNotNullOfOrNull { resolveComponent(file.project, it, tagName.substringAfter(':')) }
    }

    fun isComponent(file: VirtualFile, text: CharSequence): Boolean =
        file.nameWithoutExtension == file.parent?.name && "<fc:component" in text

    fun componentParams(project: Project, file: VirtualFile): List<ComponentParam> {
        val psi = PsiManager.getInstance(project).findFile(file) as? XmlFile ?: return emptyList()
        return PsiTreeUtil.findChildrenOfType(psi, XmlTag::class.java)
            .filter { it.name == "fc:param" }
            .mapNotNull { tag ->
                val name = tag.getAttributeValue("name") ?: return@mapNotNull null
                val optional = tag.getAttributeValue("optional")?.let { it == "1" || it == "true" } ?: false
                ComponentParam(name, tag.getAttributeValue("type"), optional, tag.getAttributeValue("default"), tag)
            }
    }

    fun findSection(project: Project, file: VirtualFile, name: String): PsiElement? {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        val m = Regex("""<f:section\s+name\s*=\s*["']${Regex.escape(name)}["']""").find(psi.viewProvider.contents) ?: return null
        return psi.findElementAt(m.range.first)
    }

    /** Ist [file] ein Partial, Layout, Template oder eine Component? */
    fun roleOf(project: Project, file: VirtualFile, text: CharSequence): FluidRole? {
        if (isComponent(file, text)) {
            // Name relativ zum Components-Ordner, z. B. Structures/Overlay/Components/Text
            val base = T3Project.get(project).componentNamespaces.values.firstOrNull { VfsUtilCore.isAncestor(it, file, true) }
            val rel = base?.let { file.parent?.let { dir -> VfsUtilCore.getRelativePath(dir, it) } }
            return FluidRole("Component", rel ?: file.nameWithoutExtension)
        }
        val t3 = T3Project.get(project)
        for (kind in listOf("Partials", "Layouts", "Templates")) {
            val root = t3.roots(kind, file).firstOrNull { VfsUtilCore.isAncestor(it, file, true) } ?: continue
            val rel = VfsUtilCore.getRelativePath(file, root) ?: continue
            return FluidRole(kind, rel.removeSuffix(".html"))
        }
        return null
    }

    /** Alle Stellen, die [file] verwenden (HTML und TypoScript). */
    fun usages(project: Project, file: VirtualFile, role: FluidRole): List<PsiElement> {
        val result = ArrayList<PsiElement>()
        val psiManager = PsiManager.getInstance(project)
        val word = file.nameWithoutExtension
        val candidates = LinkedHashSet<PsiFile>()
        val searchWord = if (role.kind == "Component") word.replaceFirstChar { it.lowercaseChar() } else word
        PsiSearchHelper.getInstance(project).processCandidateFilesForText(
            GlobalSearchScope.allScope(project), UsageSearchContext.ANY, role.kind != "Component", searchWord,
        ) { vf -> psiManager.findFile(vf)?.let { candidates += it }; true }
        for (candidate in candidates) {
            val vf = candidate.virtualFile ?: continue
            if (vf == file || vf.extension?.lowercase() != "html") continue
            val text = candidate.viewProvider.contents
            when (role.kind) {
                "Partials" -> PARTIAL.findAll(text).forEach { m ->
                    if (file in resolvePartial(project, vf, m.groupValues[2])) candidate.findElementAt(m.range.first)?.let(result::add)
                }
                "Layouts" -> LAYOUT.findAll(text).forEach { m ->
                    if (file in resolveLayout(project, vf, m.groupValues[2])) candidate.findElementAt(m.range.first)?.let(result::add)
                }
                "Component" -> {
                    COMPONENT_TAG.findAll(text).forEach { m ->
                        if (m.value.startsWith("</")) return@forEach
                        if (!m.groupValues[2].endsWith(word, ignoreCase = true)) return@forEach
                        if (resolveComponentTag(candidate, "${m.groupValues[1]}:${m.groupValues[2]}") == file) {
                            candidate.findElementAt(m.range.first + 1)?.let(result::add)
                        }
                    }
                }
            }
        }
        // TypoScript: EXT:-Pfade auf die Datei, bei Templates auch templateName
        val t3 = T3Project.get(project)
        val extPath = t3.extPathOf(file)
        val templateName = if (role.kind == "Templates") Regex("""\btemplateName\s*=\s*${Regex.escape(role.name)}\s*$""", RegexOption.MULTILINE) else null
        for (ts in t3.typoScriptFiles) {
            val text = runCatching { VfsUtilCore.loadText(ts) }.getOrNull() ?: continue
            val psi by lazy { psiManager.findFile(ts) }
            if (extPath != null) {
                var idx = text.indexOf(extPath)
                while (idx >= 0) {
                    psi?.findElementAt(idx)?.let(result::add)
                    idx = text.indexOf(extPath, idx + 1)
                }
            }
            templateName?.findAll(text)?.forEach { m -> psi?.findElementAt(m.range.first)?.let(result::add) }
        }
        return result
    }
}
