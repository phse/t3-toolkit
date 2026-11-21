package app.heckit.ide.t3toolkit.typoscript

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/** Nicht geschlossene Blöcke sowie bekannte Eigenschaften und Zahlen-Schlüssel farbig. */
class TsAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element) {
            is TsBlock -> if (!element.isClosed) {
                val brace = element.node.findChildByType(TsTokens.LBRACE)?.psi ?: return
                val beforeCondition = PsiTreeUtil.skipWhitespacesAndCommentsForward(element) is TsCondition
                holder.newAnnotation(
                    HighlightSeverity.ERROR,
                    if (beforeCondition) "Block '${element.path?.text}' is not closed before the condition - conditions inside blocks are not supported"
                    else "Missing closing brace for block '${element.path?.text}'",
                ).range(brace).create()
            }
            is TsObjectPath -> highlightSegments(element, holder)
        }
    }

    private fun highlightSegments(path: TsObjectPath, holder: AnnotationHolder) {
        val segments = path.segmentElements
        if (segments.isEmpty()) return
        val absolute = path.absoluteSegments()
        val offset = absolute.size - segments.size
        if (offset < 0) return
        segments.forEachIndexed { i, seg ->
            val text = seg.text
            val key = when {
                text.all { it.isDigit() } -> TsColors.NUMBER
                TsTypes.isKnownProperty(path.project, absolute.subList(0, offset + i), text, path.containingFile as? TsFile) -> TsColors.PROPERTY
                else -> null
            } ?: return@forEachIndexed
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(seg).textAttributes(key).create()
        }
    }
}
