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
import com.intellij.util.ProcessingContext

/** Vervollständigt die `<fc:param>`-Parameter einer Fluid Component als Attribute. */
class ComponentParamCompletion : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement().withParent(XmlAttribute::class.java),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                    val attr = parameters.position.parent as? XmlAttribute ?: return
                    if (parameters.position != attr.nameElement) return
                    val tag = attr.parent ?: return
                    val file = parameters.originalFile
                    val component = Fluid.resolveComponentTag(file, tag.name) ?: return
                    val present = tag.attributes.filter { it != attr }.map { it.name }.toSet()
                    Fluid.componentParams(file.project, component).filter { it.name !in present }.forEach { p ->
                        result.addElement(
                            LookupElementBuilder.create(p.name)
                                .withIcon(AllIcons.Nodes.Parameter)
                                .withBoldness(!p.optional && p.default == null)
                                .withTailText(if (p.optional || p.default != null) " (optional)" else " (required)", true)
                                .withTypeText(p.type)
                                .withInsertHandler(XmlAttributeInsertHandler.INSTANCE),
                        )
                    }
                }
            })
    }
}

/** Fehlende Pflichtparameter einer Fluid Component. */
class ComponentParamInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : XmlElementVisitor() {
        override fun visitXmlTag(tag: XmlTag) {
            if (':' !in tag.name) return
            val component = Fluid.resolveComponentTag(tag.containingFile, tag.name) ?: return
            val present = tag.attributes.map { it.name }.toSet()
            val missing = Fluid.componentParams(tag.project, component)
                .filter { !it.optional && it.default == null && it.name !in present }
                // content darf auch als Tag-Inhalt kommen
                .filter { !(it.name == "content" && tag.subTags.isNotEmpty() || it.name == "content" && tag.value.text.isNotBlank()) }
            if (missing.isEmpty()) return
            val anchor = tag.firstChild?.nextSibling ?: tag
            holder.registerProblem(
                anchor,
                "Missing required parameter${if (missing.size > 1) "s" else ""}: ${missing.joinToString { it.name }}",
                ProblemHighlightType.WARNING,
            )
        }
    }
}
