package app.heckit.ide.t3toolkit.xliff

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager

/** Legt ein fehlendes Label als `<trans-unit>` vor `</body>` der Sprachdatei an und öffnet es. */
class CreateLabelFix(private val file: VirtualFile, private val key: String) : LocalQuickFix {
    override fun getFamilyName() = "Create label"
    override fun getName() = "Create label '$key' in ${file.name}"
    override fun startInWriteAction() = false

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val text = document.charsSequence
        val body = text.lastIndexOf("</body>").takeIf { it >= 0 } ?: return
        val lineStart = text.lastIndexOf('\n', body - 1) + 1
        val closingIndent = text.subSequence(lineStart, body).takeWhile { it == ' ' || it == '\t' }.toString()
        // Einrückung vom letzten trans-unit übernehmen, sonst zwei Leerzeichen mehr als </body>
        val prev = Regex("""\n([ \t]*)<trans-unit\b""").findAll(text.subSequence(0, body)).lastOrNull()?.groupValues?.get(1)
        val indent = prev ?: "$closingIndent  "
        val inner = indent + (indent.drop(closingIndent.length).ifEmpty { "  " })
        val source = StringUtil.escapeXmlEntities(key)
        val snippet = "$indent<trans-unit id=\"${StringUtil.escapeXmlEntities(key)}\" resname=\"${StringUtil.escapeXmlEntities(key)}\">\n" +
            "$inner<source>$source</source>\n$indent</trans-unit>\n"
        val insertAt = if (text.subSequence(lineStart, body).isBlank()) lineStart else body
        WriteCommandAction.runWriteCommandAction(project, name, null, {
            document.insertString(insertAt, snippet)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        })
        val sourceOffset = insertAt + snippet.indexOf("<source>") + "<source>".length
        OpenFileDescriptor(project, file, sourceOffset).navigate(true)
    }
}
