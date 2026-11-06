package app.heckit.ide.t3toolkit.xliff

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * Vervollständigung in jeder Datei: nach `LLL:` die Sprachdateien der Extensions, nach `….xlf:` und in
 * `<f:translate key="…"/>` die Schlüssel mit ihrem Text.
 */
class XliffCompletion : CompletionContributor() {
    private companion object {
        val FILE_PREFIX = Regex("""LLL:(EXT:[A-Za-z0-9_/.\-]*)$""")
        val KEY_PREFIX = Regex("""LLL:(EXT:[A-Za-z0-9_]+/[^\s"'<>(){},:]+?\.xlf):([A-Za-z0-9_.\-]*)$""")
    }

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC) return
        val file = parameters.originalFile
        val project = file.project
        val text = file.viewProvider.contents
        val offset = parameters.offset
        val before = text.subSequence(maxOf(0, offset - 300), offset).toString().substringAfterLast('\n')

        KEY_PREFIX.find(before)?.let { m ->
            val target = app.heckit.ide.t3toolkit.T3Project.get(project).resolveExtPath(m.groupValues[1]) ?: return
            addKeys(project, target, result.withPrefixMatcher(m.groupValues[2]))
            return
        }
        FILE_PREFIX.find(before)?.let { m ->
            val matcher = result.withPrefixMatcher(m.groupValues[1])
            Xliff.languageFiles(project).forEach { (path, vf) ->
                matcher.addElement(
                    LookupElementBuilder.create("$path:").withPresentableText(path).withIcon(vf.fileType.icon)
                        .withInsertHandler { ctx, _ -> AutoPopupController.getInstance(ctx.project).scheduleAutoPopup(ctx.editor) },
                )
            }
            matcher.stopHere()
            return
        }
        // Kurzer Schlüssel in f:translate: der Wert unter dem Cursor
        val vf = file.virtualFile ?: return
        if (vf.extension?.lowercase() != "html") return
        val ref = Xliff.refAt(project, vf, text, offset) ?: return
        if (ref.fileRange != null || offset !in ref.keyRange.first..ref.keyRange.last + 1) return
        val target = ref.file ?: return
        addKeys(project, target, result.withPrefixMatcher(text.subSequence(ref.keyRange.first, offset).toString()))
    }

    private fun addKeys(project: Project, target: com.intellij.openapi.vfs.VirtualFile, result: CompletionResultSet) {
        Xliff.units(project, target).values.forEach { u ->
            result.addElement(
                LookupElementBuilder.create(u.id).withIcon(AllIcons.Nodes.Tag)
                    .withTailText(u.source?.let { "  " + it.take(60) }, true)
                    .withTypeText(target.name),
            )
        }
        result.stopHere()
    }
}

/** Nach `:` hinter `.xlf` bzw. nach `LLL:` die Liste gleich öffnen. */
class XliffTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped != ':') return Result.CONTINUE
        val offset = editor.caretModel.offset
        val text = editor.document.charsSequence
        val before = text.subSequence(maxOf(0, offset - 4), offset).toString()
        if (before.endsWith(".xlf") || before.endsWith("LLL")) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
            return Result.STOP
        }
        return Result.CONTINUE
    }
}
