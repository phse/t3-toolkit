package app.heckit.ide.t3toolkit.fluid

import com.intellij.navigation.GotoRelatedItem
import com.intellij.navigation.GotoRelatedProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager

/** Navigate | Related Symbol: Component ↔ Fixture, Styles, Scripts …. */
class ComponentRelatedProvider : GotoRelatedProvider() {
    override fun getItems(context: PsiElement): List<GotoRelatedItem> {
        val file = context.containingFile?.originalFile?.virtualFile ?: return emptyList()
        val psiManager = PsiManager.getInstance(context.project)
        val component = (if (file.extension?.lowercase() == "html") file else Fluid.componentOwning(file)) ?: return emptyList()
        if (!Fluid.isComponent(component, psiManager.findFile(component)?.viewProvider?.contents ?: return emptyList())) return emptyList()
        return (listOf(component) + Fluid.componentRelated(component)).filter { it != file }
            .mapNotNull { psiManager.findFile(it) }
            .map { GotoRelatedItem(it, "Fluid Component") }
    }
}
