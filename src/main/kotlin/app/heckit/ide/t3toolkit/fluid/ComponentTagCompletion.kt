package app.heckit.ide.t3toolkit.fluid

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlTokenType
import com.intellij.util.ProcessingContext

/** `<ns:` → alle Components des Namespace; Pflichtparameter werden gleich als Attribute eingefügt. */
class ComponentTagCompletion : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(XmlTokenType.XML_NAME).withParent(XmlTag::class.java),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                    val position = parameters.position
                    val typed = position.text.take((parameters.offset - position.textRange.startOffset).coerceIn(0, position.textLength))
                    val file = parameters.originalFile
                    if (':' !in typed) {
                        addPrefixes(file, result.withPrefixMatcher(typed))
                        return
                    }
                    val prefix = typed.substringBefore(':')
                    val components = Fluid.componentsFor(file, prefix)
                    if (components.isEmpty()) return
                    val matcher = result.withPrefixMatcher(typed)
                    components.forEach { (name, html) ->
                        // content kommt meist als Tag-Inhalt
                        val required = Fluid.componentParams(file.project, html).filter { !it.optional && it.default == null && it.name != "content" }.map { it.name }
                        matcher.addElement(
                            LookupElementBuilder.create("$prefix:$name")
                                .withIcon(AllIcons.Nodes.Template)
                                .withTypeText(html.parent?.parent?.name)
                                .withTailText(if (required.isEmpty()) null else " " + required.joinToString(" ") { "$it=\"\"" }, true)
                                .withInsertHandler { ctx, _ -> insertRequired(ctx, required) },
                        )
                    }
                    // Die HTML-Vorschläge passen hier nicht (unbekannte Tags mit Präfix)
                    matcher.stopHere()
                }
            })
    }

    /** `<myCo` → `myComponents:` für alle Präfixe mit Components; danach gleich die Components zeigen. */
    private fun addPrefixes(file: com.intellij.psi.PsiFile, result: CompletionResultSet) {
        ViewHelpers.namespacesFor(file).keys.forEach { prefix ->
            val count = Fluid.componentsFor(file, prefix).size
            if (count == 0) return@forEach
            result.addElement(
                LookupElementBuilder.create("$prefix:")
                    .withIcon(AllIcons.Nodes.Template)
                    .withTypeText("$count component${if (count == 1) "" else "s"}")
                    .withInsertHandler { ctx, _ -> AutoPopupController.getInstance(ctx.project).scheduleAutoPopup(ctx.editor) },
            )
        }
    }
}

/** Fügt Pflicht-Attribute hinter dem eben eingefügten Tag-Namen ein (Components und ViewHelper). */
internal fun insertRequired(ctx: InsertionContext, required: List<String>) {
    if (required.isEmpty()) return
    val doc = ctx.document
    val next = doc.charsSequence.getOrNull(ctx.tailOffset)
    if (next != null && (next.isLetterOrDigit() || next == '=')) return
    // Tag hat schon Attribute (Name eines bestehenden Tags ersetzt) - nichts doppelt einfügen
    val rest = doc.charsSequence.subSequence(ctx.tailOffset, doc.textLength)
    val tagEnd = rest.indexOfFirst { it == '>' || it == '<' }.let { if (it < 0) rest.length else it }
    if ('=' in rest.subSequence(0, tagEnd)) return
    val attrs = required.joinToString("") { " $it=\"\"" }
    val at = ctx.tailOffset
    doc.insertString(at, attrs)
    ctx.editor.caretModel.moveToOffset(at + required.first().length + 3)
}
