package app.heckit.ide.t3toolkit.fluid

import app.heckit.ide.t3toolkit.T3Project
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/** Namen in `partial="…"`, `<f:layout name="…"/>` und `section="…"` (Tag- und Inline-Schreibweise). */
class FluidNameCompletion : CompletionContributor() {
    private companion object {
        val PARTIAL = Regex("""\bpartial\s*[=:]\s*["']([^"'{}]*)$""")
        val SECTION = Regex("""\bsection\s*[=:]\s*["']([^"'{}]*)$""")
        val LAYOUT = Regex("""\bf:layout\b[^>}]*?\bname\s*[=:]\s*["']([^"'{}]*)$""")
        val SECTION_DEF = Regex("""<f:section\s+name\s*=\s*["']([^"']+)["']""")
    }

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC) return
        // Das Fluid-Plugin injiziert {…} als eigene Fragmente - auf die HTML-Datei umrechnen
        var file: PsiFile = parameters.originalFile
        var offset = parameters.offset
        val ilm = InjectedLanguageManager.getInstance(file.project)
        if (ilm.isInjectedFragment(file)) {
            offset = ilm.injectedToHost(file, offset)
            file = ilm.getTopLevelFile(file) ?: return
        }
        val vf = file.virtualFile ?: return
        if (vf.extension?.lowercase() != "html") return
        val text = file.viewProvider.contents
        val before = text.subSequence(maxOf(0, offset - 400), offset).toString()
        val chunk = before.substring(maxOf(before.lastIndexOf('<'), before.lastIndexOf('{'), 0))
        val project = file.project

        PARTIAL.find(chunk)?.let { m ->
            add(result, m.groupValues[1], names(T3Project.get(project).roots("Partials", vf)), "Partial")
            return
        }
        LAYOUT.find(chunk)?.let { m ->
            add(result, m.groupValues[1], names(T3Project.get(project).roots("Layouts", vf)), "Layout")
            return
        }
        SECTION.find(chunk)?.let { m ->
            // Section im Partial derselben Anweisung, sonst in der Datei selbst
            val partial = Fluid.PARTIAL.find(chunk)?.groupValues?.get(2)
            val files = if (partial != null) Fluid.resolvePartial(project, vf, partial) else listOf(vf)
            val sections = files.flatMap { f ->
                val t = if (f == vf) text else runCatching { VfsUtilCore.loadText(f) }.getOrDefault("")
                SECTION_DEF.findAll(t).map { it.groupValues[1] to f.name }.toList()
            }.distinctBy { it.first }
            val matcher = result.withPrefixMatcher(m.groupValues[1])
            sections.forEach { (name, where) ->
                matcher.addElement(LookupElementBuilder.create(name).withIcon(AllIcons.Nodes.Tag).withTypeText(where))
            }
            matcher.stopHere()
        }
    }

    /** Name relativ zum Root-Ordner (ohne `.html`) → Root, die ersten Roots gewinnen. */
    private fun names(roots: List<VirtualFile>): Map<String, VirtualFile> {
        val result = LinkedHashMap<String, VirtualFile>()
        roots.forEach { root ->
            VfsUtilCore.iterateChildrenRecursively(root, null) { f ->
                if (!f.isDirectory && f.extension?.lowercase() == "html") {
                    VfsUtilCore.getRelativePath(f, root)?.removeSuffix(".html")?.let { result.putIfAbsent(it, root) }
                }
                true
            }
        }
        return result
    }

    private fun add(result: CompletionResultSet, typed: String, names: Map<String, VirtualFile>, kind: String) {
        val matcher = result.withPrefixMatcher(typed)
        names.forEach { (name, root) ->
            val where = root.parent?.parent?.parent?.name
            matcher.addElement(LookupElementBuilder.create(name).withIcon(AllIcons.FileTypes.Html).withTypeText(where ?: kind))
        }
        matcher.stopHere()
    }
}
