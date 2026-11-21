package app.heckit.ide.t3toolkit.fluid

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText

/** Einfache Fluid-Aufrufe zwischen Tag- und Inline-Schreibweise umwandeln. */
internal object FluidSyntax {
    private val NUMBER = Regex("""-?\d+(\.\d+)?""")

    /** `{f:name(a: 'x', b: y)}` bzw. `{value -> f:name(a: 'x')}` (ein Schritt, keine Verschachtelung im Wert). */
    data class Inline(val prefix: String, val name: String, val args: List<Pair<String, String>>, val value: String?)

    private val CALL = Regex("""^\s*(?:(.+?)\s*->\s*)?([A-Za-z0-9_]+):([A-Za-z0-9_.]+)\s*\((.*)\)\s*$""", RegexOption.DOT_MATCHES_ALL)

    fun parseInline(content: String): Inline? {
        val m = CALL.matchEntire(content) ?: return null
        val value = m.groups[1]?.value?.trim()
        if (value != null && ("->" in value || '(' in value)) return null
        val args = splitArgs(m.groupValues[4]) ?: return null
        return Inline(m.groupValues[2], m.groupValues[3], args, value)
    }

    /** `a: 'x', b: {c: d}` → [(a, 'x'), (b, {c: d})]; null bei unklarer Syntax. */
    fun splitArgs(text: String): List<Pair<String, String>>? {
        if (text.isBlank()) return emptyList()
        val parts = ArrayList<String>()
        var depth = 0
        var quote: Char? = null
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                sb.append(c)
                if (c == '\\' && i + 1 < text.length) { sb.append(text[i + 1]); i++ } else if (c == quote) quote = null
            } else when (c) {
                '\'', '"' -> { quote = c; sb.append(c) }
                '{', '(', '[' -> { depth++; sb.append(c) }
                '}', ')', ']' -> { depth--; sb.append(c) }
                ',' -> if (depth == 0) { parts += sb.toString(); sb.clear() } else sb.append(c)
                else -> sb.append(c)
            }
            i++
        }
        if (quote != null || depth != 0) return null
        parts += sb.toString()
        return parts.map { p ->
            val key = p.substringBefore(':', "").trim()
            if (key.isEmpty() || !key.all { it.isLetterOrDigit() || it == '_' }) return null
            key to p.substringAfter(':').trim()
        }
    }

    /** Inline-Wert → Attributwert: `'x'` → x, sonst `{…}`. */
    fun toAttribute(value: String): String {
        val v = value.trim()
        if (v.length >= 2 && (v.startsWith("'") && v.endsWith("'") || v.startsWith("\"") && v.endsWith("\""))) {
            return v.substring(1, v.length - 1).replace("\\'", "'").replace("\"", "&quot;")
        }
        if (NUMBER.matches(v) || v == "true" || v == "false" || v == "TRUE" || v == "FALSE") return v
        return "{$v}".replace("\"", "'")
    }

    /** Attributwert → Inline-Wert: `{x}` → x, sonst `'x'`. */
    fun toInline(value: String): String {
        val v = value.trim()
        if (v.startsWith("{") && v.endsWith("}") && FluidInline.matchingEnd(v, 0) == v.length) return v.substring(1, v.length - 1)
        if (NUMBER.matches(v)) return v
        return "'" + v.replace("\\", "\\\\").replace("'", "\\'") + "'"
    }

    /** Ausdruck `{…}` an [offset] in [text] (innerhalb von [from]..[to]). */
    fun expressionAt(text: CharSequence, offset: Int, from: Int, to: Int): FluidInline.Expression? =
        FluidInline.expressions(text, from, to).firstOrNull { offset >= it.start && offset <= it.end }
}

/** `{f:translate(key: 'x')}` → `<f:translate key="x" />`, `{text -> f:format.html()}` → `<f:format.html>{text}</f:format.html>`. */
class FluidInlineToTagIntention : PsiElementBaseIntentionAction() {
    override fun getFamilyName() = "Convert Fluid inline syntax to tag"
    override fun getText() = familyName

    private fun find(editor: Editor, element: PsiElement): Pair<FluidInline.Expression, FluidSyntax.Inline>? {
        val text = PsiTreeUtil.getParentOfType(element, XmlText::class.java, false) ?: return null
        if (PsiTreeUtil.getParentOfType(element, XmlAttributeValue::class.java, false) != null) return null
        val file = element.containingFile ?: return null
        if (!FluidInline.isFluidFile(file)) return null
        val content = file.viewProvider.contents
        val e = FluidSyntax.expressionAt(content, editor.caretModel.offset, text.textRange.startOffset, text.textRange.endOffset) ?: return null
        val inline = FluidSyntax.parseInline(content.subSequence(e.start + 1, e.end - 1).toString()) ?: return null
        return e to inline
    }

    override fun isAvailable(project: Project, editor: Editor, element: PsiElement) = find(editor, element) != null

    override fun invoke(project: Project, editor: Editor, element: PsiElement) {
        val (e, inline) = find(editor, element) ?: return
        val tag = "${inline.prefix}:${inline.name}"
        val attrs = inline.args.joinToString("") { (k, v) -> " $k=\"${FluidSyntax.toAttribute(v)}\"" }
        val replacement = if (inline.value == null) "<$tag$attrs />" else "<$tag$attrs>{${inline.value}}</$tag>"
        val doc = editor.document
        doc.replaceString(e.start, e.end, replacement)
        PsiDocumentManager.getInstance(project).commitDocument(doc)
    }

    override fun startInWriteAction() = true
}

/** `<f:translate key="x" />` → `{f:translate(key: 'x')}`, `<f:format.html>{text}</f:format.html>` → `{text -> f:format.html()}`. */
class FluidTagToInlineIntention : PsiElementBaseIntentionAction() {
    override fun getFamilyName() = "Convert Fluid tag to inline syntax"
    override fun getText() = familyName

    private fun find(element: PsiElement): Pair<XmlTag, String>? {
        val tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java, false) ?: return null
        // Nur auf dem Tag-Namen bzw. im Start-Tag anbieten, nicht im ganzen Inhalt
        val startTagEnd = tag.node.findChildByType(com.intellij.psi.xml.XmlTokenType.XML_TAG_END)?.textRange?.endOffset
            ?: tag.node.findChildByType(com.intellij.psi.xml.XmlTokenType.XML_EMPTY_ELEMENT_END)?.textRange?.endOffset ?: return null
        if (element.textRange.startOffset >= startTagEnd) return null
        val parts = tag.name.split(':', limit = 2)
        if (parts.size != 2 || parts[0] == "fc") return null
        val file = tag.containingFile ?: return null
        if (!FluidInline.isFluidFile(file)) return null
        if (ViewHelpers.resolve(file, parts[0], parts[1]).isEmpty() && ViewHelpers.all(file, parts[0]).isNotEmpty()) return null
        if (tag.subTags.isNotEmpty()) return null
        val args = tag.attributes.joinToString(", ") { a -> "${a.name}: ${FluidSyntax.toInline(a.value.orEmpty())}" }
        val content = tag.value.text.trim()
        val call = "${tag.name}($args)"
        val inline = when {
            content.isEmpty() -> "{$call}"
            content.startsWith("{") && FluidInline.matchingEnd(content, 0) == content.length -> "{${content.substring(1, content.length - 1)} -> $call}"
            '{' !in content && '<' !in content -> "{'" + content.replace("'", "\\'") + "' -> $call}"
            else -> return null
        }
        return tag to inline
    }

    override fun isAvailable(project: Project, editor: Editor, element: PsiElement) = find(element) != null

    override fun invoke(project: Project, editor: Editor, element: PsiElement) {
        val (tag, inline) = find(element) ?: return
        val doc = editor.document
        doc.replaceString(tag.textRange.startOffset, tag.textRange.endOffset, inline)
        PsiDocumentManager.getInstance(project).commitDocument(doc)
    }

    override fun startInWriteAction() = true
}

/** HTML-Kommentare landen in der Ausgabe - `<!-- x -->` → `<f:comment>x</f:comment>`. */
class FluidCommentIntention : PsiElementBaseIntentionAction() {
    override fun getFamilyName() = "Convert to Fluid comment (not rendered)"
    override fun getText() = familyName

    private fun comment(element: PsiElement): PsiComment? {
        val c = PsiTreeUtil.getParentOfType(element, PsiComment::class.java, false) ?: return null
        if (!c.text.startsWith("<!--") || !c.text.endsWith("-->")) return null
        return c.takeIf { FluidInline.isFluidFile(it.containingFile) }
    }

    override fun isAvailable(project: Project, editor: Editor, element: PsiElement) = comment(element) != null

    override fun invoke(project: Project, editor: Editor, element: PsiElement) {
        val c = comment(element) ?: return
        val inner = c.text.removePrefix("<!--").removeSuffix("-->")
        editor.document.replaceString(c.textRange.startOffset, c.textRange.endOffset, "<f:comment>$inner</f:comment>")
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
    }

    override fun startInWriteAction() = true
}
