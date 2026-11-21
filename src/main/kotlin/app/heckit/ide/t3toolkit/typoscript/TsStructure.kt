package app.heckit.ide.t3toolkit.typoscript

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.navigation.PsiElementNavigationItem
import com.intellij.navigation.ItemPresentationProviders
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.NavigatablePsiElement
import javax.swing.Icon

class TsStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (psiFile !is TsFile) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                object : StructureViewModelBase(psiFile, editor, TsStructureElement(psiFile)), StructureViewModel.ElementInfoProvider {
                    init { withSuitableClasses(TsStatement::class.java, TsCondition::class.java, TsImport::class.java, TsInclude::class.java) }
                    override fun isAlwaysShowsPlus(element: StructureViewTreeElement) = false
                    override fun isAlwaysLeaf(element: StructureViewTreeElement) = element.value !is TsBlock && element.value !is TsFile
                }
        }
    }
}

private class TsStructureElement(private val element: PsiElement) : StructureViewTreeElement {
    override fun getValue(): Any = element
    override fun navigate(requestFocus: Boolean) { (element as? NavigatablePsiElement)?.navigate(requestFocus) }
    override fun canNavigate() = (element as? NavigatablePsiElement)?.canNavigate() == true
    override fun canNavigateToSource() = canNavigate()

    override fun getPresentation(): ItemPresentation = when (element) {
        is TsFile -> element.presentation ?: simple(element.name, null, AllIcons.FileTypes.Text)
        is TsBlock -> simple(element.path?.text.orEmpty(), null, AllIcons.Nodes.Folder)
        is TsStatement -> {
            val p = element.presentation
            simple(p.presentableText.orEmpty(), p.locationString, AllIcons.Nodes.Field)
        }
        is TsCondition -> simple(element.text.trim(), null, AllIcons.Nodes.Lambda)
        is TsImport -> simple("@import " + element.importPath.orEmpty(), null, AllIcons.Nodes.Include)
        is TsInclude -> simple("INCLUDE " + element.source?.first.orEmpty(), null, AllIcons.Nodes.Include)
        else -> simple(element.text.take(40), null, null)
    }

    override fun getChildren(): Array<TreeElement> {
        val children = when (element) {
            is TsFile -> element.statements
            is TsBlock -> element.statements
            else -> emptyList()
        }
        return children.map { TsStructureElement(it) }.toTypedArray()
    }

    private fun simple(text: String, location: String?, icon: Icon?) = object : ItemPresentation {
        override fun getPresentableText() = text
        override fun getLocationString() = location
        override fun getIcon(unused: Boolean) = icon
    }
}
