package app.heckit.ide.t3toolkit.xliff

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiModificationTracker

/** Klappt `LLL:EXT:…xlf:key` und kurze `f:translate`-Schlüssel ein und zeigt den Text des Labels. */
class XliffFolding : FoldingBuilderEx() {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val file = root.containingFile ?: return emptyArray()
        val project = file.project
        if (DumbService.isDumb(project)) return emptyArray()
        if (file.language != file.viewProvider.baseLanguage) return emptyArray()
        if (InjectedLanguageManager.getInstance(project).isInjectedFragment(file)) return emptyArray()
        val vf = file.virtualFile ?: return emptyArray()
        if (vf.extension?.lowercase() == "xlf") return emptyArray()
        val node = root.node ?: return emptyArray()
        val deps = setOf<Any>(PsiModificationTracker.getInstance(project))
        return Xliff.refs(project, vf, document.charsSequence).mapNotNull { ref ->
            val target = ref.file ?: return@mapNotNull null
            val unit = Xliff.units(project, target)[ref.key] ?: return@mapNotNull null
            val text = unit.source ?: unit.target ?: return@mapNotNull null
            // Ganze Angabe: ab "LLL:" bis Ende Schlüssel, bei kurzen Schlüsseln nur der Schlüssel
            val start = ref.fileRange?.let { it.first - 4 } ?: ref.keyRange.first
            val range = TextRange(start, ref.keyRange.last + 1)
            if (range.isEmpty || !node.textRange.contains(range)) return@mapNotNull null
            val label = if (text.length > 50) text.take(49) + "…" else text
            FoldingDescriptor(node, range, null, "„$label“", true, deps)
        }.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode) = "…"
    override fun isCollapsedByDefault(node: ASTNode) = true
}
