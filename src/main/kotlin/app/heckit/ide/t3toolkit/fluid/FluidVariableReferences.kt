package app.heckit.ide.t3toolkit.fluid

import app.heckit.ide.t3toolkit.NavTarget
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.util.ProcessingContext

/**
 * Referenzen auf Fluid-Variablen in Attributwerten: Strg/⌘ unterstreicht nur das Wort (`teasers`),
 * nicht das ganze Attribut. Ziele wie bei [FluidVariables.targets].
 */
class FluidVariableReferences : PsiReferenceContributor() {
    private val ident = Regex("[A-Za-z_][A-Za-z0-9_]*")

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        val provider = object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                val file = element.containingFile ?: return PsiReference.EMPTY_ARRAY
                if (file.virtualFile?.extension?.lowercase() != "html") return PsiReference.EMPTY_ARRAY
                val text = element.text
                if ('{' !in text) return PsiReference.EMPTY_ARRAY
                return ident.findAll(text).mapNotNull { m ->
                    val name = FluidVariables.nameAt(text, m.range.first) ?: return@mapNotNull null
                    if (name != m.value) return@mapNotNull null
                    VariableReference(element, TextRange(m.range.first, m.range.last + 1), name)
                }.toList().toTypedArray()
            }
        }
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(XmlAttributeValue::class.java), provider)
    }

    private class VariableReference(element: PsiElement, range: TextRange, private val name: String) :
        PsiPolyVariantReferenceBase<PsiElement>(element, range, true) {
        override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
            val file = element.containingFile
            val offset = element.textRange.startOffset + rangeInElement.startOffset
            return FluidVariables.targets(file, offset, name)
                .map { t -> if (t is com.jetbrains.php.lang.psi.elements.PhpNamedElement) t else NavTarget.of(t) }
                .map { PsiElementResolveResult(it) }.toTypedArray()
        }
    }
}
