package app.heckit.ide.t3toolkit.fluid

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.XmlAttributeInsertHandler
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlTokenType
import com.intellij.util.ProcessingContext

/** `<f:fo` → `f:format.html` …; in einem ViewHelper-Tag die Argumente aus `registerArgument()`. */
class ViewHelperCompletion : CompletionContributor() {
    init {
        // Tag-Name
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(XmlTokenType.XML_NAME).withParent(XmlTag::class.java),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                    val position = parameters.position
                    val typed = position.text.take((parameters.offset - position.textRange.startOffset).coerceIn(0, position.textLength))
                    val file = parameters.originalFile
                    if (':' !in typed) {
                        addWithoutColon(file, typed, result)
                        return
                    }
                    val prefix = typed.substringBefore(':')
                    val viewHelpers = ViewHelpers.all(file, prefix)
                    if (viewHelpers.isEmpty()) return
                    val matcher = result.withPrefixMatcher(typed)
                    viewHelpers.forEach { (name, cls) ->
                        val required = ViewHelpers.arguments(cls).filter { it.required }.map { it.name }
                        matcher.addElement(
                            LookupElementBuilder.create("$prefix:$name")
                                .withIcon(AllIcons.Nodes.Tag)
                                .withTypeText(cls.namespaceName.trim('\\').substringAfterLast('\\').ifEmpty { null })
                                .withTailText(if (required.isEmpty()) null else " " + required.joinToString(" ") { "$it=\"\"" }, true)
                                .withInsertHandler { ctx, _ -> insertRequired(ctx, required) },
                        )
                    }
                    matcher.stopHere()
                }
            })
        // Attribut-Name
        extend(CompletionType.BASIC, PlatformPatterns.psiElement().withParent(XmlAttribute::class.java),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                    val attr = parameters.position.parent as? XmlAttribute ?: return
                    if (parameters.position != attr.nameElement) return
                    val tag = attr.parent ?: return
                    val (prefix, name) = tag.name.split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: return
                    val file = parameters.originalFile
                    if (Fluid.resolveComponentTag(file, tag.name) != null) return
                    val cls = ViewHelpers.resolve(file, prefix, name).firstOrNull() ?: return
                    val present = tag.attributes.filter { it != attr }.map { it.name }.toSet()
                    val args = ViewHelpers.arguments(cls)
                    args.filter { it.name !in present }.forEach { a ->
                        result.addElement(
                            LookupElementBuilder.create(a.name)
                                .withIcon(AllIcons.Nodes.Parameter)
                                .withBoldness(a.required)
                                .withTailText(a.description?.let { "  " + it.take(60) } ?: if (a.required) " (required)" else null, true)
                                .withTypeText(a.type)
                                .withInsertHandler(XmlAttributeInsertHandler.INSTANCE),
                        )
                    }
                    // Die HTML-Vorschläge (alle Attribute) passen für ViewHelper nicht; tag-basierte erlauben HTML-Attribute
                    if (!ViewHelpers.isTagBased(cls)) result.stopHere()
                }
            })
    }
}

/**
 * `<frend` → `f:render`: ohne Doppelpunkt alle ViewHelper aller Präfixe, gefunden über Präfix+Name ohne Trennzeichen
 * (`frender`, `fformathtml`) oder den Namen allein (`render`). Die HTML-Vorschläge bleiben.
 */
private fun addWithoutColon(file: com.intellij.psi.PsiFile, typed: String, result: CompletionResultSet) {
    if (typed.isEmpty()) return
    val matcher = result.withPrefixMatcher(typed)
    ViewHelpers.namespacesFor(file).keys.forEach { prefix ->
        ViewHelpers.all(file, prefix).forEach { (name, cls) ->
            val required = ViewHelpers.arguments(cls).filter { it.required }.map { it.name }
            matcher.addElement(
                LookupElementBuilder.create("$prefix:$name")
                    .withLookupStrings(listOf(prefix + name.replace(".", ""), name, name.replace(".", "")))
                    .withIcon(AllIcons.Nodes.Tag)
                    .withTailText(if (required.isEmpty()) null else " " + required.joinToString(" ") { "$it=\"\"" }, true)
                    .withInsertHandler { ctx, _ -> insertRequired(ctx, required) },
            )
        }
    }
}

/** Fehlende Pflichtargumente eines ViewHelpers. */
class ViewHelperArgumentInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : XmlElementVisitor() {
        override fun visitXmlTag(tag: XmlTag) {
            val parts = tag.name.split(':', limit = 2)
            if (parts.size != 2) return
            val file = tag.containingFile
            if (Fluid.resolveComponentTag(file, tag.name) != null) return
            val cls = ViewHelpers.resolve(file, parts[0], parts[1]).firstOrNull() ?: return
            val present = tag.attributes.map { it.name }.toSet()
            val hasContent = tag.subTags.isNotEmpty() || tag.value.text.isNotBlank()
            val missing = ViewHelpers.arguments(cls).filter { it.required && it.name !in present }
                // Wert als Tag-Inhalt (value, content …) ist erlaubt
                .filter { !(hasContent && it.name in setOf("value", "content", "subject", "html", "string")) }
            if (missing.isEmpty()) return
            holder.registerProblem(
                tag.firstChild?.nextSibling ?: tag,
                "Missing required argument${if (missing.size > 1) "s" else ""}: ${missing.joinToString { it.name }}",
                ProblemHighlightType.WARNING,
            )
        }
    }
}
