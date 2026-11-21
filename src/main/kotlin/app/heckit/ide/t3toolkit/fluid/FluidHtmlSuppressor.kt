package app.heckit.ide.t3toolkit.fluid

import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag

/**
 * HTML-Inspections, die Fluid nicht kennen:
 * - „Unresolved file reference“ hält Fluid-Ausdrücke wie `href="{f:uri.typolink(…)}"` für Dateipfade - nur Attributwerte
 *   mit `{…}` ausblenden, normale Pfade prüft die IDE weiter.
 * - „Namespace 'f' is not bound“ meldet jedes `<f:…>`-Tag ohne `xmlns:f` als Fehler - in Fluid-Templates für Präfixe,
 *   die Fluid kennt (TYPO3-Vorgaben, ext_localconf.php, Einstellungen, `{namespace}`), ausblenden.
 */
class FluidHtmlSuppressor : InspectionSuppressor {
    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean = when (toolId) {
        TOOL_ID -> PsiTreeUtil.getParentOfType(element, XmlAttributeValue::class.java, false)?.let { FLUID_EXPRESSION.containsMatchIn(it.value) } == true
        UNBOUND_NS -> {
            val file = element.containingFile
            val prefix = PsiTreeUtil.getParentOfType(element, XmlTag::class.java, false)?.namespacePrefix
            file != null && !prefix.isNullOrEmpty() && FluidInline.isFluidFile(file) && prefix in ViewHelpers.namespacesFor(file)
        }
        else -> false
    }

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> = SuppressQuickFix.EMPTY_ARRAY

    companion object {
        const val TOOL_ID = "HtmlUnknownTarget"
        const val UNBOUND_NS = "XmlUnboundNsPrefix"
        private val FLUID_EXPRESSION = Regex("""\{[^}]*}""")
    }
}
