package app.heckit.ide.t3toolkit.xliff

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * In XLF-Dateien: Labels der Ausgangsdatei, die in einer Übersetzung (`de.locallang.xlf` …) fehlen, und
 * Labels einer Übersetzung, die es in der Ausgangsdatei nicht (mehr) gibt.
 */
class XliffTranslationInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val vf = file.virtualFile ?: return null
        if (vf.extension?.lowercase() != "xlf") return null
        val project = file.project
        val problems = ArrayList<ProblemDescriptor>()
        fun report(unit: TransUnit, message: String) {
            problems += manager.createProblemDescriptor(file, TextRange(unit.offset, unit.offset + unit.id.length), message,
                ProblemHighlightType.WARNING, isOnTheFly)
        }
        val units = Xliff.units(project, vf)
        if (Xliff.isTranslation(vf)) {
            val default = Xliff.defaultOf(vf).takeIf { it != vf } ?: return null
            val known = Xliff.units(project, default)
            units.values.filter { it.id !in known }.forEach { report(it, "Label '${it.id}' does not exist in ${default.name}") }
        } else {
            val translations = Xliff.translationsOf(vf).map { it.name.substringBefore('.') to Xliff.units(project, it) }
            if (translations.isEmpty()) return null
            units.values.forEach { u ->
                val missing = translations.filter { u.id !in it.second }.map { it.first }
                if (missing.isNotEmpty()) report(u, "Label '${u.id}' is not translated: ${missing.joinToString(", ")}")
            }
        }
        return problems.toTypedArray()
    }
}
