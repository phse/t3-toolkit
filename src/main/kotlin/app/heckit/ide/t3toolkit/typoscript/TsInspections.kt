package app.heckit.ide.t3toolkit.typoscript

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor

/** `@import` und `<INCLUDE_TYPOSCRIPT>`, deren Datei oder Ordner fehlt. */
class TsImportInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            val project = element.project
            val context = element.containingFile?.virtualFile
            when (element) {
                is TsImport -> {
                    val pathElement = element.pathElement ?: return
                    val path = element.importPath ?: return
                    if (!(path.startsWith("EXT:") || path.startsWith("./") || path.startsWith("../"))) return
                    if (path.startsWith("EXT:") && path.removePrefix("EXT:").substringBefore('/') !in app.heckit.ide.t3toolkit.T3Project.get(project).extensions) {
                        holder.registerProblem(pathElement, "Unknown extension '${path.removePrefix("EXT:").substringBefore('/')}'", ProblemHighlightType.ERROR)
                        return
                    }
                    if (!TsImports.exists(project, path, context)) holder.registerProblem(pathElement, "File or folder not found: $path", ProblemHighlightType.ERROR)
                }
                is TsInclude -> {
                    val (source, range) = element.source ?: return
                    if (!source.startsWith("EXT:")) return
                    if (TsImports.resolve(project, source, context).isEmpty()) {
                        holder.registerProblem(element, "File or folder not found: $source", ProblemHighlightType.ERROR, TextRange(range.first, range.last + 1))
                    }
                }
            }
        }
    }
}

/** `{$constant}`, das weder in einer Konstanten-Datei noch in den Site-Settings vorkommt (kann aus der Datenbank kommen). */
class TsConstantInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            if (element.node?.elementType != TsTokens.CONSTANT) return
            val text = element.text
            if ("??" in text) return // Ersatzwert vorhanden
            val name = TsGotoHandler.constantName(text)
            if (name.isEmpty()) return
            val project = element.project
            if (TsIndex.definitions(project, TsKind.CONSTANTS, name).isNotEmpty()) return
            if (TsSiteSettings.all(project).containsKey(name)) return
            holder.registerProblem(element, "Constant '$name' is not defined in any constants file or site setting", ProblemHighlightType.WEAK_WARNING)
        }
    }
}

/** `<INCLUDE_TYPOSCRIPT:` ist seit TYPO3 v13 veraltet und fällt mit v14 weg - Schnellkorrektur zu `@import`. */
class TsIncludeInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            if (element !is TsInclude) return
            val fix = TsIncludeToImportFix.importFor(element)?.let { TsIncludeToImportFix() }
            holder.registerProblem(element, "<INCLUDE_TYPOSCRIPT> is deprecated since TYPO3 v13 and removed in v14 - use @import",
                ProblemHighlightType.LIKE_DEPRECATED, *listOfNotNull(fix).toTypedArray())
        }
    }
}

class TsIncludeToImportFix : LocalQuickFix {
    override fun getFamilyName() = "Replace with @import"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val include = descriptor.psiElement as? TsInclude ?: return
        val replacement = importFor(include) ?: return
        val doc = PsiDocumentManager.getInstance(project).getDocument(include.containingFile) ?: return
        val range = include.textRange
        doc.replaceString(range.startOffset, range.endOffset, replacement)
        PsiDocumentManager.getInstance(project).commitDocument(doc)
    }

    companion object {
        private val CONDITION = Regex("""condition\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)

        /** `@import '…'` für das Include (bei condition="…" in eine Bedingung gefasst), null wenn nicht umsetzbar. */
        fun importFor(include: TsInclude): String? {
            val (source, _) = include.source ?: return null
            val path = source.trim()
            if (path.isEmpty()) return null
            val target = if (include.isDirectory) {
                val ext = include.extensions.firstOrNull { it == "typoscript" || it == "tsconfig" } ?: include.extensions.firstOrNull() ?: "typoscript"
                path.trimEnd('/') + "/*." + ext
            } else path
            val import = "@import '$target'"
            val condition = CONDITION.find(include.text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return import
            val cond = if (condition.startsWith("[")) condition else "[$condition]"
            return "$cond\n$import\n[END]"
        }
    }
}
