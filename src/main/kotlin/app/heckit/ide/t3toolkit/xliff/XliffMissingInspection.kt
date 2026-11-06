package app.heckit.ide.t3toolkit.xliff

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/** `LLL:`-Angaben und `f:translate`-Schlüssel, deren Sprachdatei oder Schlüssel es nicht gibt. */
class XliffMissingInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val vf = file.virtualFile ?: return null
        if (vf.extension?.lowercase() == "xlf") return null
        // Template-Sprachen haben mehrere PSI-Wurzeln - nur einmal je Datei prüfen
        if (file.language != file.viewProvider.baseLanguage) return null
        val project = file.project
        if (InjectedLanguageManager.getInstance(project).isInjectedFragment(file)) return null
        val problems = ArrayList<ProblemDescriptor>()
        fun report(range: IntRange, message: String, vararg fixes: com.intellij.codeInspection.LocalQuickFix) {
            if (range.isEmpty()) return
            problems += manager.createProblemDescriptor(file, TextRange(range.first, range.last + 1), message,
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly, *fixes)
        }
        for (ref in Xliff.refs(project, vf, file.viewProvider.contents)) {
            val target = ref.file
            if (target == null) {
                // Kurze Schlüssel ohne locallang.xlf: nicht melden (Datei kann per extensionName woanders liegen)
                ref.fileRange?.let { report(it, "Language file '${ref.filePath}' not found") }
                continue
            }
            if (ref.key.isEmpty()) continue
            if (ref.key !in Xliff.units(project, target)) report(ref.keyRange, "Label '${ref.key}' not found in ${ref.filePath}", CreateLabelFix(target, ref.key))
        }
        return problems.toTypedArray()
    }
}
