package app.heckit.ide.t3toolkit.typoscript

import app.heckit.ide.t3toolkit.NavTarget
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Strg/⌘-Klick in TypoScript: Pfadsegmente zu den Stellen, an denen das Objekt gesetzt wird (bei Kopien `<`/`=<` die Quelle),
 * Konstanten zu ihrer Definition bzw. den Site-Settings, PHP-Klassen und -Methoden in Werten, Datenprozessor-Aliase und
 * `@import`-Pfade (auch mit `*`).
 */
class TsGotoHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(source: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val element = source ?: return null
        val file = element.containingFile as? TsFile ?: return null
        val project = element.project
        val kind = TsKind.of(file)
        val targets: List<PsiElement> = when (element.node.elementType) {
            TsTokens.IDENTIFIER -> pathTargets(project, kind, element)
            TsTokens.CONSTANT -> constantTargets(project, constantName(element.text))
            TsTokens.VALUE -> TsPhp.targetsAt(project, element.text, offset - element.textRange.startOffset)
            TsTokens.IMPORT_PATH -> {
                val path = element.text.trim('\'', '"')
                if (path.startsWith("EXT:") && '*' !in path && !path.endsWith("/")) return null // übernimmt der EXT:-Handler
                val psi = PsiManager.getInstance(project)
                TsImports.resolve(project, path, file.virtualFile).mapNotNull { if (it.isDirectory) psi.findDirectory(it) else psi.findFile(it) }
            }
            else -> emptyList()
        }
        return targets.map { if (it is TsObjectPath) NavTarget.of(it) else it }
            .takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private fun pathTargets(project: Project, kind: TsKind, segment: PsiElement): List<PsiElement> {
        val path = segment.parent as? TsObjectPath ?: return emptyList()
        val absolute = path.absoluteSegmentsUpTo(segment)
        val own = segment.textRange.startOffset
        val ownFile = segment.containingFile.virtualFile
        val defs = TsIndex.definitions(project, kind, absolute.joinToString("."))
            .filter { !(it.file == ownFile && it.def.offset == own) }
            // Ausdrückliche Zuweisungen und Kopien zuerst, Blöcke danach
            .sortedBy { if (it.def.op == TsDef.BLOCK) 1 else 0 }
        return defs.mapNotNull { TsIndex.element(project, it) }
    }

    companion object {
        fun constantName(text: String) = text.removePrefix("{$").removeSuffix("}").substringBefore("??").trim()

        fun constantTargets(project: Project, name: String): List<PsiElement> {
            val result = ArrayList<PsiElement>()
            TsIndex.definitions(project, TsKind.CONSTANTS, name).filter { it.def.op != TsDef.BLOCK }
                .mapNotNullTo(result) { TsIndex.element(project, it) }
            TsSiteSettings.all(project)[name].orEmpty().sortedByDescending { it.isDefinition }.mapTo(result) { it.element }
            return result
        }
    }
}

/** Doku beim Darüberfahren: Eigenschaften (Typ, Beschreibung), Objekttypen, Konstanten mit Wert, Funktionen. */
class TsDocumentationProvider : AbstractDocumentationProvider() {
    private class DocElement(private val context: PsiElement, val title: String, val html: String) : FakePsiElement() {
        override fun getParent(): PsiElement = context
        override fun getName() = title
        override fun getPresentableText() = title
        override fun isValid() = context.isValid
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? = (element as? DocElement)?.html
    override fun generateHoverDoc(element: PsiElement, originalElement: PsiElement?): String? = (element as? DocElement)?.html
    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? = null

    override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int): PsiElement? {
        if (file !is TsFile) return null
        val element = file.findElementAt(targetOffset) ?: return null
        val project = file.project
        val kind = TsKind.of(file)
        return when (element.node.elementType) {
            TsTokens.IDENTIFIER -> {
                val path = element.parent as? TsObjectPath ?: return null
                val absolute = path.absoluteSegmentsUpTo(element)
                val parentType = TsTypes.typeOf(project, absolute.dropLast(1), kind)
                val prop = TsRef.prop(parentType, element.text)?.takeIf { it.name != "*" } ?: return null
                val type = TsTypes.typeOf(project, absolute, kind)
                DocElement(element, element.text, propDoc(element.text, prop, type, absolute.joinToString(".")))
            }
            TsTokens.OBJECT_TYPE -> {
                val name = element.text.trim()
                val desc = TsRef.contentObjects[name]
                if (desc == null && name !in TsRef.types) return null
                DocElement(element, name, typeDoc(name, desc))
            }
            TsTokens.CONSTANT -> {
                val name = TsGotoHandler.constantName(element.text)
                val sb = StringBuilder(DocumentationMarkup.DEFINITION_START).append("<b>{$").append(esc(name)).append("}</b>")
                    .append(DocumentationMarkup.DEFINITION_END).append(DocumentationMarkup.SECTIONS_START)
                TsIndex.definitions(project, TsKind.CONSTANTS, name).filter { it.def.op == TsDef.ASSIGN }.take(5).forEach {
                    sb.append(section(it.file.name, "<code>${esc(it.def.value)}</code>"))
                }
                TsSiteSettings.all(project)[name].orEmpty().take(5).forEach { s ->
                    val where = s.element.containingFile?.virtualFile?.let { vf -> vf.parent?.name + "/" + vf.name }.orEmpty()
                    sb.append(section(where, listOfNotNull(s.label?.let(::esc), s.default?.let { "<code>${esc(it)}</code>" }).joinToString(" - ")))
                }
                sb.append(DocumentationMarkup.SECTIONS_END)
                DocElement(element, name, sb.toString())
            }
            TsTokens.FUNCTION_NAME -> {
                val name = element.text.trim()
                val desc = TsRef.functions[name] ?: return null
                DocElement(element, name, DocumentationMarkup.DEFINITION_START + "<b>${esc(name)}()</b>" + DocumentationMarkup.DEFINITION_END +
                    DocumentationMarkup.CONTENT_START + esc(desc) + DocumentationMarkup.CONTENT_END)
            }
            else -> null
        }
    }

    private fun esc(s: String) = StringUtil.escapeXmlEntities(s)
    private fun section(title: String, value: String) =
        DocumentationMarkup.SECTION_HEADER_START + esc(title) + DocumentationMarkup.SECTION_SEPARATOR + value + DocumentationMarkup.SECTION_END

    private fun propDoc(name: String, prop: TsRef.Prop, type: String?, path: String): String {
        val sb = StringBuilder(DocumentationMarkup.DEFINITION_START).append("<b>").append(esc(name)).append("</b>")
        val shown = type ?: prop.childType ?: prop.valueType
        sb.append(": ").append(esc(shown.removePrefix("DP:"))).append("<br>").append(esc(path)).append(DocumentationMarkup.DEFINITION_END)
        if (prop.description.isNotEmpty()) sb.append(DocumentationMarkup.CONTENT_START).append(esc(prop.description)).append(DocumentationMarkup.CONTENT_END)
        sb.append(DocumentationMarkup.SECTIONS_START)
        sb.append(section("Property of", esc(prop.owner.removePrefix("DP:").substringBefore('.'))))
        if (prop.values.isNotEmpty()) sb.append(section("Values", prop.values.joinToString(", ") { "<code>${esc(it)}</code>" }))
        if (prop.childType == "stdWrap" && prop.valueType != "stdWrap") sb.append(section("Sub-properties", "stdWrap"))
        sb.append(DocumentationMarkup.SECTIONS_END)
        return sb.toString()
    }

    private fun typeDoc(name: String, desc: String?): String {
        val sb = StringBuilder(DocumentationMarkup.DEFINITION_START).append("<b>").append(esc(name)).append("</b>").append(DocumentationMarkup.DEFINITION_END)
        desc?.let { sb.append(DocumentationMarkup.CONTENT_START).append(esc(it)).append(DocumentationMarkup.CONTENT_END) }
        val props = TsRef.props(name).filter { it.name != "*" && it.name != "#" && it.owner == name }
        if (props.isNotEmpty()) {
            sb.append("<table>")
            props.forEach { p ->
                sb.append("<tr><td><code>").append(esc(p.name)).append("</code></td><td>").append(esc(p.description)).append("</td></tr>")
            }
            sb.append("</table>")
        }
        TsRef.types[name]?.parent?.let { sb.append("<p>Also all properties of <b>").append(esc(it)).append("</b>.</p>") }
        return sb.toString()
    }
}
