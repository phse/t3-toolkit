package app.heckit.ide.t3toolkit.services

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.SmartPointerManager
import com.intellij.openapi.util.TextRange
import app.heckit.ide.t3toolkit.php.PhpAttributes
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YamlPsiElementVisitor

/** Schlägt vor, Services.yaml-Einträge (event.listener, console.command …) als PHP-Attribut an die Klasse zu schreiben. */
class ServicesAttributeInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!ServicesIndex.isServicesFile(holder.file.virtualFile)) return PsiElementVisitor.EMPTY_VISITOR
        return object : YamlPsiElementVisitor() {
            override fun visitKeyValue(keyValue: YAMLKeyValue) {
                val conversion = AttributeConversion.analyze(keyValue) ?: return
                val key = keyValue.key ?: return
                holder.registerProblem(
                    key,
                    "Can be registered with PHP attribute${if (conversion.attributes.size > 1) "s" else ""} " +
                        conversion.attributes.joinToString { "#[" + it.fqn.substringAfterLast('\\') + "]" },
                    ProblemHighlightType.WEAK_WARNING,
                    ConvertToAttributeFix(conversion.title),
                )
            }
        }
    }
}

class ConvertToAttributeFix(private val title: String) : LocalQuickFix {
    override fun getFamilyName() = "Move Services.yaml entry to PHP attribute"
    override fun getName() = title
    override fun startInWriteAction() = false
    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor) = IntentionPreviewInfo.EMPTY

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val keyValue = descriptor.psiElement?.parent as? YAMLKeyValue ?: return
        val c = AttributeConversion.analyze(keyValue) ?: return
        val docs = PsiDocumentManager.getInstance(project)
        val yamlDoc = docs.getDocument(keyValue.containingFile) ?: return
        val phpFile = c.phpClass.containingFile
        val phpDoc = docs.getDocument(phpFile) ?: return
        val classPointer = SmartPointerManager.createPointer(c.phpClass)
        WriteCommandAction.runWriteCommandAction(project, title, null, {
            // YAML zuerst: Bereiche aus der PSI, von hinten nach vorn löschen
            val mapping = keyValue.value
            when {
                c.removeService -> yamlDoc.deleteLines(keyValue, withBlankLineBefore = true)
                mapping != null && c.remove.containsAll((mapping as? org.jetbrains.yaml.psi.YAMLMapping)?.keyValues.orEmpty()) -> {
                    yamlDoc.replaceString(keyValue.key!!.textRange.endOffset, mapping.textRange.endOffset, " ~")
                }
                else -> c.remove.sortedByDescending { it.textRange.startOffset }.forEach { yamlDoc.deleteLines(it) }
            }
            docs.commitDocument(yamlDoc)
            val cls = classPointer.element ?: return@runWriteCommandAction
            PhpAttributes.addToClass(phpDoc, cls, c.attributes)
            docs.commitDocument(phpDoc)
        }, keyValue.containingFile, phpFile)
        classPointer.element?.let { OpenFileDescriptor(project, phpFile.virtualFile, it.textRange.startOffset).navigate(false) }
    }

    /** Löscht die ganzen Zeilen, über die sich [element] erstreckt. */
    private fun Document.deleteLines(element: PsiElement, withBlankLineBefore: Boolean = false) {
        val range: TextRange = element.textRange
        val firstLine = getLineNumber(range.startOffset)
        val blankBefore = withBlankLineBefore && firstLine > 0 &&
            charsSequence.subSequence(getLineStartOffset(firstLine - 1), getLineEndOffset(firstLine - 1)).isBlank()
        val start = getLineStartOffset(if (blankBefore) firstLine - 1 else firstLine)
        val endLine = getLineNumber(range.endOffset.coerceAtMost(textLength))
        val end = if (endLine + 1 < lineCount) getLineStartOffset(endLine + 1) else textLength
        deleteString(start, end)
    }
}
