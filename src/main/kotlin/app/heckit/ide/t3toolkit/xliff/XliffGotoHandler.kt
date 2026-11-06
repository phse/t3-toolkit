package app.heckit.ide.t3toolkit.xliff

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.injected.editor.EditorWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

/** Strg/⌘-Klick auf `LLL:EXT:…xlf:key` bzw. `<f:translate key="…"/>`: Datei bzw. `<trans-unit>`. */
class XliffGotoHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(source: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        var element = source ?: return null
        var offset = offset
        val ilm = InjectedLanguageManager.getInstance(element.project)
        element.containingFile?.let { f ->
            if (ilm.isInjectedFragment(f)) {
                val hostOffset = if (editor is EditorWindow) ilm.injectedToHost(element, offset) else offset
                element = ilm.getTopLevelFile(element)?.findElementAt(hostOffset) ?: return null
                offset = hostOffset
            }
        }
        val file = element.containingFile?.originalFile ?: return null
        val project = element.project
        val ref = Xliff.refAt(project, file.virtualFile, file.viewProvider.contents, offset) ?: return null
        val target = ref.file ?: return null
        val psi = PsiManager.getInstance(project).findFile(target) ?: return null
        val onFile = ref.fileRange?.let { offset in it.first..it.last + 1 } == true
        if (onFile || ref.key.isEmpty()) return arrayOf(psi)
        val unit = Xliff.units(project, target)[ref.key] ?: return arrayOf(psi)
        val leaf = psi.findElementAt(unit.offset) ?: return arrayOf(psi)
        // XLF ist meist als XML zugeordnet; sonst (TextMate o. Ä.) auf die Stelle im Text springen
        return arrayOf(PsiTreeUtil.getParentOfType(leaf, XmlTag::class.java) ?: app.heckit.ide.t3toolkit.NavTarget.of(leaf))
    }
}
