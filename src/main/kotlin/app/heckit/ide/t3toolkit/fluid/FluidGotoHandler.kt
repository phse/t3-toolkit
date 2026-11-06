package app.heckit.ide.t3toolkit.fluid

import app.heckit.ide.t3toolkit.T3Project
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.injected.editor.EditorWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag

/**
 * Strg/⌘-Klick in Fluid-Templates: Partials, Layouts, Sections, Fluid Components samt Parametern
 * und `EXT:`-Pfade (letztere in jeder Datei).
 */
class FluidGotoHandler : GotoDeclarationHandler {
    private companion object {
        val INLINE_NAME = Regex("""(?<![A-Za-z0-9_.:/])([A-Za-z0-9_]+):([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*)(?=\s*\()""")
        val INLINE_CALL = Regex("""(?<![A-Za-z0-9_.:/])([A-Za-z0-9_]+):([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*)\s*\(([^()]*)\)""")
        val ARG_NAME = Regex("""(?:^|[,\s(])([A-Za-z0-9_]+)\s*:""")

        fun splitTag(name: String): Pair<String, String>? =
            name.substringBefore(':', "").takeIf { it.isNotEmpty() }?.let { it to name.substringAfter(':') }
    }

    override fun getGotoDeclarationTargets(source: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        // Blätter (XmlToken o. Ä.) navigiert die IDE nicht zuverlässig an - auf das umschließende Element gehen
        val r = targets(source, offset, editor)?.map { t ->
            if (t is com.intellij.psi.PsiFileSystemItem || t is com.jetbrains.php.lang.psi.elements.PhpNamedElement) t
            else app.heckit.ide.t3toolkit.NavTarget.of(t)
        }?.distinctBy { (it as? app.heckit.ide.t3toolkit.NavTarget)?.textOffset?.let { o -> it.parent.containingFile to o } ?: it }
            ?.toTypedArray<PsiElement>()
        return r
    }

    private fun targets(source: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        var element = source ?: return null
        var offset = offset
        // Das Fluid-Plugin injiziert {…}-Ausdrücke als eigene Fragmente - auf die HTML-Datei umrechnen
        val ilm = InjectedLanguageManager.getInstance(element.project)
        var injected = false
        element.containingFile?.let { f ->
            if (ilm.isInjectedFragment(f)) {
                injected = true
                val hostOffset = if (editor is EditorWindow) ilm.injectedToHost(element, offset) else offset
                val host = ilm.getTopLevelFile(element) ?: return null
                element = host.findElementAt(hostOffset) ?: return null
                offset = hostOffset
            }
        }
        val file = element.containingFile?.originalFile ?: return null
        val vf = file.virtualFile ?: return null
        val project = element.project
        val psiManager = PsiManager.getInstance(project)
        val text = file.viewProvider.contents
        val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        val line = text.subSequence(lineStart, lineEnd)
        val col = offset - lineStart
        fun hit(m: MatchResult, group: Int = 2) = col in m.groups[group]!!.range.first..m.groups[group]!!.range.last + 1

        // EXT:-Pfade überall
        Fluid.EXT_PATH.findAll(line).firstOrNull { col in it.range.first..it.range.last + 1 }?.let { m ->
            // LLL:EXT:… übernimmt XliffGotoHandler
            if (line.subSequence(0, m.range.first).endsWith("LLL:")) return null
            val target = T3Project.get(project).resolveExtPath(m.value) ?: return null
            val psi = if (target.isDirectory) psiManager.findDirectory(target) else psiManager.findFile(target)
            return psi?.let { arrayOf(it) }
        }

        if (vf.extension?.lowercase() != "html") return null

        Fluid.PARTIAL.findAll(line).firstOrNull { hit(it) }?.let { m ->
            return Fluid.resolvePartial(project, vf, m.groupValues[2]).mapNotNull(psiManager::findFile).toTypedArray()
        }
        Fluid.LAYOUT.findAll(line).firstOrNull { hit(it) }?.let { m ->
            return Fluid.resolveLayout(project, vf, m.groupValues[2]).mapNotNull(psiManager::findFile).toTypedArray()
        }
        Fluid.SECTION.findAll(line).firstOrNull { hit(it) }?.let { m ->
            // Section im Partial derselben Anweisung, sonst in der Datei selbst
            val chunkStart = maxOf(line.lastIndexOf('<', m.range.first), line.lastIndexOf('{', m.range.first), 0)
            val chunkEnd = line.indexOfAny(charArrayOf('>', '}'), m.range.last).let { if (it < 0) line.length else it }
            val partial = Fluid.PARTIAL.find(line.subSequence(chunkStart, chunkEnd))
            val files = if (partial != null) Fluid.resolvePartial(project, vf, partial.groupValues[2]) else listOf(vf)
            return files.mapNotNull { Fluid.findSection(project, it, m.groupValues[2]) }.toTypedArray()
        }

        // Attribut eines Component- oder ViewHelper-Tags
        PsiTreeUtil.getParentOfType(element, XmlAttribute::class.java, false)?.let { attr ->
            if (element != attr.nameElement && element.parent != attr.nameElement) return@let
            val tag = attr.parent ?: return@let
            Fluid.resolveComponentTag(file, tag.name)?.let { component ->
                return Fluid.componentParams(project, component).filter { it.name == attr.name }.map { it.tag }.toTypedArray()
            }
            val (prefix, name) = splitTag(tag.name) ?: return null
            return ViewHelpers.resolve(file, prefix, name).mapNotNull { ViewHelpers.findArgument(it, attr.name) }.take(1).toTypedArray()
        }
        // Inline-Argument: {f:format.date(date: x, format: 'd.m.Y')}
        INLINE_CALL.findAll(line).forEach { m ->
            val args = m.groups[3] ?: return@forEach
            if (col !in args.range) return@forEach
            ARG_NAME.findAll(args.value).firstOrNull { col - args.range.first in it.groups[1]!!.range.first..it.groups[1]!!.range.last + 1 }?.let { a ->
                return ViewHelpers.resolve(file, m.groupValues[1], m.groupValues[2]).mapNotNull { ViewHelpers.findArgument(it, a.groupValues[1]) }.take(1).toTypedArray()
            }
        }
        // Fluid-Variable: {title}, each="{components}", arguments="{x: item}"
        // Mehrzeilige Ausdrücke (arguments="{a: x,\n b: y}"): ab Anfang des Attributwerts bzw. Textblocks lesen
        val windowStart = minOf(lineStart, element.textRange.startOffset.coerceAtMost(offset))
        FluidVariables.nameAt(text.subSequence(windowStart, lineEnd), offset - windowStart)?.let { name ->
            // In Attributwerten übernimmt FluidVariableReferences (unterstreicht nur das Wort). Im Text zwischen Tags
            // (XmlText trägt keine beigesteuerten Referenzen) und in Fragmenten, die das Fluid-Plugin injiziert, hier.
            if (!injected && PsiTreeUtil.getParentOfType(element, com.intellij.psi.xml.XmlAttributeValue::class.java, false) != null) return null
            return FluidVariables.targets(file, offset, name).toTypedArray().takeIf { it.isNotEmpty() }
        }
        // Tag oder Inline-Aufruf: Component, sonst ViewHelper
        (Fluid.COMPONENT_TAG.findAll(line) + INLINE_NAME.findAll(line))
            .firstOrNull { col in it.groups[1]!!.range.first..it.groups[2]!!.range.last + 1 }?.let { m ->
                val prefix = m.groupValues[1]
                val name = m.groupValues[2]
                Fluid.resolveComponentTag(file, "$prefix:$name")?.let { return psiManager.findFile(it)?.let { f -> arrayOf(f) } }
                return ViewHelpers.resolve(file, prefix, name).toTypedArray<PsiElement>().takeIf { it.isNotEmpty() }
            }
        return null
    }
}
