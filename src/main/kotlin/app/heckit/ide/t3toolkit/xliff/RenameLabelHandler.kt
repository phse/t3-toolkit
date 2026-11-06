package app.heckit.ide.t3toolkit.xliff

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.refactoring.rename.RenameHandler

/** Umbenennen (Shift+F6) eines Labels: `<trans-unit>` in allen Sprachdateien und jede Verwendung. */
class RenameLabelHandler : RenameHandler {
    private data class Target(val default: VirtualFile, val key: String)

    private fun target(dataContext: DataContext): Target? {
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return null
        val file = CommonDataKeys.PSI_FILE.getData(dataContext) ?: return null
        return target(file, editor.caretModel.offset)
    }

    private fun target(file: PsiFile, offset: Int): Target? {
        val vf = file.virtualFile ?: return null
        val project = file.project
        if (vf.extension?.lowercase() == "xlf") {
            val unit = Xliff.units(project, vf).values.firstOrNull { offset in it.offset..it.offset + it.id.length } ?: return null
            return Target(Xliff.defaultOf(vf), unit.id)
        }
        val ref = Xliff.refAt(project, vf, file.viewProvider.contents, offset) ?: return null
        if (ref.fileRange?.let { offset in it.first..it.last } == true || ref.key.isEmpty()) return null
        return Target(ref.file ?: return null, ref.key)
    }

    override fun isAvailableOnDataContext(dataContext: DataContext) = target(dataContext) != null
    override fun isRenaming(dataContext: DataContext) = isAvailableOnDataContext(dataContext)

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext?) {
        val t = (if (editor != null && file != null) target(file, editor.caretModel.offset) else null) ?: return
        val existing = Xliff.units(project, t.default).keys
        val newKey = Messages.showInputDialog(project, "Rename label '${t.key}' in ${t.default.name} and all its usages to:",
            "Rename Label", null, t.key, object : InputValidator {
                override fun checkInput(input: String?) = input != null && input.matches(Regex("""[A-Za-z0-9_.\-]+""")) && input !in existing
                override fun canClose(input: String?) = checkInput(input)
            }) ?: return
        if (newKey == t.key) return
        rename(project, t.default, t.key, newKey)
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        dataContext?.let { target(it) }?.let { t ->
            invoke(project, CommonDataKeys.EDITOR.getData(dataContext), CommonDataKeys.PSI_FILE.getData(dataContext), dataContext)
        }
    }

    companion object {
        /** Ohne Dialog - auch für Tests. */
        fun rename(project: Project, default: VirtualFile, oldKey: String, newKey: String) {
            // Erst alle Stellen sammeln, dann schreiben (die Verwendungen hängen am Cache)
            val edits = LinkedHashMap<VirtualFile, MutableList<IntRange>>()
            (listOf(default) + Xliff.translationsOf(default)).forEach { xlf ->
                val text = FileDocumentManager.getInstance().getDocument(xlf)?.charsSequence ?: return@forEach
                Regex("""<trans-unit\b[^>]*>""").findAll(text).forEach { tag ->
                    Regex("""\b(?:id|resname)\s*=\s*(["'])${Regex.escape(oldKey)}\1""").findAll(tag.value).forEach { a ->
                        val start = tag.range.first + a.range.first + a.value.indexOf(a.groupValues[1]) + 1
                        edits.getOrPut(xlf) { ArrayList() } += start until start + oldKey.length
                    }
                }
            }
            val usageFiles = Xliff.usages(project, default)[oldKey].orEmpty().mapNotNull { it.containingFile?.virtualFile }.distinct()
            usageFiles.forEach { vf ->
                val text = FileDocumentManager.getInstance().getDocument(vf)?.charsSequence ?: return@forEach
                Xliff.refs(project, vf, text).filter { it.file == default && it.key == oldKey }.forEach { edits.getOrPut(vf) { ArrayList() } += it.keyRange }
            }
            WriteCommandAction.runWriteCommandAction(project, "Rename Label", null, {
                edits.forEach { (vf, ranges) ->
                    val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return@forEach
                    ranges.distinct().sortedByDescending { it.first }.forEach { r -> doc.replaceString(r.first, r.last + 1, newKey) }
                    PsiDocumentManager.getInstance(project).commitDocument(doc)
                }
            }, *edits.keys.mapNotNull { PsiManager.getInstance(project).findFile(it) }.toTypedArray())
        }
    }
}
