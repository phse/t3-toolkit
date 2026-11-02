package app.heckit.ide.t3toolkit.fluid

import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue

/**
 * Die HTML-Inspection „Unresolved file reference“ hält Fluid-Ausdrücke wie `href="{f:uri.typolink(…)}"` für Dateipfade.
 * Nur Attributwerte mit `{…}` ausblenden - normale Pfade prüft die IDE weiter.
 */
class FluidHtmlSuppressor : InspectionSuppressor {
    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        if (toolId != TOOL_ID) return false
        val value = PsiTreeUtil.getParentOfType(element, XmlAttributeValue::class.java, false) ?: return false
        return FLUID_EXPRESSION.containsMatchIn(value.value)
    }

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> = SuppressQuickFix.EMPTY_ARRAY

    companion object {
        const val TOOL_ID = "HtmlUnknownTarget"
        private val FLUID_EXPRESSION = Regex("""\{[^}]*}""")
    }
}
