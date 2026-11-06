package app.heckit.ide.t3toolkit.icons

import app.heckit.ide.t3toolkit.NavTarget
import app.heckit.ide.t3toolkit.T3Project
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.icons.AllIcons
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

private fun host(file: PsiFile, offset: Int): Pair<PsiFile, Int>? {
    val ilm = InjectedLanguageManager.getInstance(file.project)
    if (!ilm.isInjectedFragment(file)) return file to offset
    return (ilm.getTopLevelFile(file) ?: return null) to ilm.injectedToHost(file, offset)
}

/** Strg/⌘-Klick auf `identifier` von `<core:icon>` → Eintrag in `Configuration/Icons.php`. */
class IconGotoHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(source: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val element = source ?: return null
        val (file, hostOffset) = host(element.containingFile ?: return null, offset) ?: return null
        if (file.virtualFile?.extension?.lowercase() != "html") return null
        val (id, _) = T3IconRegistry.useAt(file.viewProvider.contents, hostOffset) ?: return null
        val def = T3IconRegistry.all(file.project)[id] ?: return null
        val leaf = PsiManager.getInstance(file.project).findFile(def.file)?.findElementAt(def.offset) ?: return null
        return arrayOf(NavTarget.of(leaf))
    }
}

/** Identifier in `<core:icon identifier="…"/>` mit Quelle als Hinweis. */
class IconCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC) return
        val (file, offset) = host(parameters.originalFile, parameters.offset) ?: return
        if (file.virtualFile?.extension?.lowercase() != "html") return
        val (_, range) = T3IconRegistry.useAt(file.viewProvider.contents, offset) ?: return
        val typed = file.viewProvider.contents.subSequence(range.first, offset).toString()
        val matcher = result.withPrefixMatcher(typed)
        val t3 = T3Project.get(file.project)
        T3IconRegistry.all(file.project).values.forEach { def ->
            matcher.addElement(
                LookupElementBuilder.create(def.identifier).withIcon(AllIcons.FileTypes.Image)
                    .withTypeText(def.source?.substringAfterLast('/') ?: t3.extensionOf(def.file)?.first),
            )
        }
        matcher.stopHere()
    }
}
