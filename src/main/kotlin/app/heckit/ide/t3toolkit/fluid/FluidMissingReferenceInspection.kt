package app.heckit.ide.t3toolkit.fluid

import app.heckit.ide.t3toolkit.T3Project
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/** Partials, Layouts und Components, die es nicht gibt. */
class FluidMissingReferenceInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val vf = file.virtualFile ?: return null
        if (vf.extension?.lowercase() != "html") return null
        if (InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)) return null
        val project = file.project
        val text = file.viewProvider.contents
        val t3 = T3Project.get(project)
        val problems = ArrayList<ProblemDescriptor>()
        fun report(range: IntRange, message: String) {
            problems += manager.createProblemDescriptor(file, TextRange(range.first, range.last + 1), message,
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly)
        }
        // Nur prüfen, wo wir die Root-Ordner kennen - sonst wäre jede Angabe „unbekannt“
        if (t3.roots("Partials", vf).isNotEmpty()) {
            Fluid.PARTIAL.findAll(text).forEach { m ->
                if (Fluid.resolvePartial(project, vf, m.groupValues[2]).isEmpty()) report(m.groups[2]!!.range, "Partial '${m.groupValues[2]}' not found")
            }
        }
        if (t3.roots("Layouts", vf).isNotEmpty()) {
            Fluid.LAYOUT.findAll(text).forEach { m ->
                if (Fluid.resolveLayout(project, vf, m.groupValues[2]).isEmpty()) report(m.groups[2]!!.range, "Layout '${m.groupValues[2]}' not found")
            }
        }
        val namespaces by lazy { ViewHelpers.namespacesFor(file) }
        Fluid.COMPONENT_TAG.findAll(text).forEach { m ->
            val prefix = m.groupValues[1]
            val name = m.groupValues[2]
            if (prefix == "f" || prefix == "fc") return@forEach
            // Nur Präfixe, die auf einen Components-Ordner zeigen; ViewHelper-Namespaces bleiben unberührt
            if (namespaces[prefix].orEmpty().none { Fluid.componentBase(project, it) != null }) return@forEach
            if (Fluid.resolveComponentTag(file, "$prefix:$name") != null) return@forEach
            if (ViewHelpers.resolve(file, prefix, name).isNotEmpty()) return@forEach
            report(m.groups[2]!!.range, "Component '$prefix:$name' not found")
        }
        return problems.toTypedArray()
    }
}
