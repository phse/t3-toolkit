package app.heckit.ide.t3toolkit

import app.heckit.ide.t3toolkit.fluid.Fluid
import app.heckit.ide.t3toolkit.fluid.ViewHelpers
import app.heckit.ide.t3toolkit.xliff.Xliff
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.jetbrains.php.lang.psi.elements.PhpClass

/**
 * Doku beim Darüberfahren: ViewHelper (Beschreibung und Argumente aus der Klasse) und Labels
 * (`LLL:…`, `f:translate`, `<trans-unit>`) mit allen Übersetzungen.
 */
class T3DocumentationProvider : AbstractDocumentationProvider() {
    /** Träger der fertigen Doku - die IDE fragt danach [generateDoc] mit diesem Element. */
    private class DocElement(private val context: PsiElement, val title: String, val html: String) : FakePsiElement() {
        override fun getParent(): PsiElement = context
        override fun getName() = title
        override fun getPresentableText() = title
        override fun isValid() = context.isValid
    }

    private companion object {
        val INLINE_NAME = Regex("""(?<![A-Za-z0-9_.:/])([A-Za-z0-9_]+):([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*)(?=\s*\()""")
        fun esc(s: String?) = StringUtil.escapeXmlEntities(s.orEmpty())
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? = (element as? DocElement)?.html
    override fun generateHoverDoc(element: PsiElement, originalElement: PsiElement?): String? = (element as? DocElement)?.html
    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? = null

    override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int): PsiElement? {
        val context = contextElement ?: return null
        var host = file
        var offset = targetOffset
        val ilm = InjectedLanguageManager.getInstance(file.project)
        if (ilm.isInjectedFragment(file)) {
            offset = ilm.injectedToHost(file, offset)
            host = ilm.getTopLevelFile(file) ?: return null
        }
        val vf = host.virtualFile ?: return null
        val text = host.viewProvider.contents
        val project = file.project

        // Labels
        if (vf.extension?.lowercase() == "xlf") {
            val unit = Xliff.units(project, vf).values.firstOrNull { offset in it.offset..it.offset + it.id.length } ?: return null
            return DocElement(context, unit.id, labelDoc(project, Xliff.defaultOf(vf), unit.id, vf.name))
        }
        Xliff.refAt(project, vf, text, offset)?.let { ref ->
            val target = ref.file ?: return null
            if (ref.key.isEmpty()) return null
            return DocElement(context, ref.key, labelDoc(project, target, ref.key, ref.filePath))
        }
        if (vf.extension?.lowercase() != "html") return null

        // ViewHelper-Attribut
        val hostElement = host.findElementAt(offset)
        PsiTreeUtil.getParentOfType(hostElement, XmlAttribute::class.java, false)?.let { attr ->
            if (hostElement?.parent != attr.nameElement && hostElement != attr.nameElement) return@let
            val tag = attr.parent ?: return@let
            val parts = tag.name.split(':', limit = 2).takeIf { it.size == 2 } ?: return@let
            val cls = ViewHelpers.resolve(host, parts[0], parts[1]).firstOrNull() ?: return@let
            val arg = ViewHelpers.arguments(cls).firstOrNull { it.name == attr.name } ?: return@let
            val html = DocumentationMarkup.DEFINITION_START + "<b>${esc(arg.name)}</b>" + (arg.type?.let { ": ${esc(it)}" } ?: "") +
                DocumentationMarkup.DEFINITION_END + DocumentationMarkup.CONTENT_START + esc(arg.description) + DocumentationMarkup.CONTENT_END +
                DocumentationMarkup.SECTIONS_START +
                section("Required", if (arg.required) "yes" else "no") +
                (arg.default?.let { section("Default", "<code>${esc(it)}</code>") } ?: "") +
                section("ViewHelper", "${esc(tag.name)} (${esc(cls.name)})") +
                DocumentationMarkup.SECTIONS_END
            return DocElement(context, arg.name, html)
        }
        // ViewHelper-Tag oder Inline-Aufruf
        val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        val line = text.subSequence(lineStart, lineEnd)
        val col = offset - lineStart
        val m = (Fluid.COMPONENT_TAG.findAll(line) + INLINE_NAME.findAll(line))
            .firstOrNull { col in it.groups[1]!!.range.first..it.groups[2]!!.range.last + 1 } ?: return null
        val name = "${m.groupValues[1]}:${m.groupValues[2]}"
        if (Fluid.resolveComponentTag(host, name) != null) return null
        val cls = ViewHelpers.resolve(host, m.groupValues[1], m.groupValues[2]).firstOrNull() ?: return null
        return DocElement(context, name, viewHelperDoc(name, cls))
    }

    private fun section(title: String, value: String) =
        DocumentationMarkup.SECTION_HEADER_START + title + DocumentationMarkup.SECTION_SEPARATOR + value + DocumentationMarkup.SECTION_END

    private fun viewHelperDoc(name: String, cls: PhpClass): String {
        val sb = StringBuilder()
        sb.append(DocumentationMarkup.DEFINITION_START).append("<b>").append(esc(name)).append("</b><br>")
            .append(esc(cls.fqn.trimStart('\\'))).append(DocumentationMarkup.DEFINITION_END)
        ViewHelpers.description(cls)?.let { d ->
            // Absätze erhalten, Beispiele (Zeilen mit Tags) als Code
            sb.append(DocumentationMarkup.CONTENT_START)
            d.split(Regex("""\n\s*\n""")).take(4).forEach { p ->
                if ('<' in p || '{' in p) sb.append("<pre><code>").append(esc(p)).append("</code></pre>")
                else sb.append("<p>").append(esc(p.replace('\n', ' '))).append("</p>")
            }
            sb.append(DocumentationMarkup.CONTENT_END)
        }
        val args = ViewHelpers.arguments(cls)
        if (args.isNotEmpty()) {
            sb.append("<table>")
            sb.append("<tr><td><b>Argument</b></td><td><b>Type</b></td><td><b>Description</b></td></tr>")
            args.sortedByDescending { it.required }.forEach { a ->
                sb.append("<tr><td><code>").append(esc(a.name)).append("</code>").append(if (a.required) " *" else "").append("</td><td>")
                    .append(esc(a.type)).append("</td><td>").append(esc(a.description))
                    .append(a.default?.let { " <i>(default ${esc(it)})</i>" } ?: "").append("</td></tr>")
            }
            sb.append("</table><p>* required</p>")
        }
        if (ViewHelpers.isTagBased(cls)) sb.append("<p>Tag-based: also accepts HTML attributes.</p>")
        return sb.toString()
    }

    private fun labelDoc(project: com.intellij.openapi.project.Project, default: com.intellij.openapi.vfs.VirtualFile, key: String, where: String): String {
        val sb = StringBuilder()
        sb.append(DocumentationMarkup.DEFINITION_START).append("<b>").append(esc(key)).append("</b><br>").append(esc(where))
            .append(DocumentationMarkup.DEFINITION_END)
        sb.append("<table>")
        val unit = Xliff.units(project, default)[key]
        sb.append("<tr><td><b>default</b></td><td>").append(unit?.source?.let(::esc) ?: "<i>missing</i>").append("</td></tr>")
        Xliff.translationsOf(default).forEach { t ->
            val u = Xliff.units(project, t)[key]
            sb.append("<tr><td><b>").append(esc(t.name.substringBefore('.'))).append("</b></td><td>")
                .append((u?.target ?: u?.source)?.let(::esc) ?: "<i>missing</i>").append("</td></tr>")
        }
        sb.append("</table>")
        return sb.toString()
    }
}
