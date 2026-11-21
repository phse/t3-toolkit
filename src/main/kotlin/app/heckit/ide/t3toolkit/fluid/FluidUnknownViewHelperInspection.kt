package app.heckit.ide.t3toolkit.fluid

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

/**
 * ViewHelper, die es unter ihrem Präfix nicht gibt (`<f:fomat.html>`, `{f:transalte(…)}`). Geprüft werden nur Präfixe,
 * für die das Projekt ViewHelper-Klassen kennt - fehlt z. B. `vendor/`, bleibt alles still.
 */
class FluidUnknownViewHelperInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val vf = file.virtualFile ?: return null
        if (vf.extension?.lowercase() != "html") return null
        if (InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)) return null
        if (file.language != file.viewProvider.baseLanguage) return null
        val known = HashMap<String, Map<String, *>>()
        val complete = HashMap<String, Boolean>()
        fun viewHelpers(prefix: String) = known.getOrPut(prefix) { ViewHelpers.all(file, prefix) }
        val problems = ArrayList<ProblemDescriptor>()
        fun check(prefix: String, name: String, range: TextRange) {
            if (prefix == "fc" || name.isEmpty()) return
            val all = viewHelpers(prefix)
            if (all.isEmpty() || name in all || !complete.getOrPut(prefix) { ViewHelpers.isComplete(file, prefix) }) return
            if (Fluid.resolveComponentTag(file, "$prefix:$name") != null) return
            if (ViewHelpers.resolve(file, prefix, name).isNotEmpty()) return
            val similar = all.keys.firstOrNull { it.equals(name, ignoreCase = true) }
            problems += manager.createProblemDescriptor(file, range,
                "Unknown ViewHelper '$prefix:$name'" + (similar?.let { " - did you mean '$prefix:$it'?" } ?: ""),
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly)
        }
        PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).forEach { tag ->
            val parts = tag.name.split(':', limit = 2)
            if (parts.size != 2) return@forEach
            val nameElement = tag.node.findChildByType(com.intellij.psi.xml.XmlTokenType.XML_NAME) ?: return@forEach
            check(parts[0], parts[1], nameElement.textRange)
        }
        if (FluidInline.isFluidFile(file)) {
            val text = file.viewProvider.contents
            FluidInline.expressions(text).forEach { e ->
                FluidInline.parts(text, e).filter { it.kind == FluidInline.Kind.VIEWHELPER }.forEach { p ->
                    val full = text.subSequence(p.start, p.end).toString()
                    check(full.substringBefore(':'), full.substringAfter(':'), TextRange(p.start, p.end))
                }
            }
        }
        return problems.toTypedArray()
    }
}
