package app.heckit.ide.t3toolkit.fluid

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactoryBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.Consumer

/**
 * Steht der Cursor an `{`/`}` bzw. `(`/`)` eines Fluid-Ausdrucks, wird das Gegenstück mit hervorgehoben (die HTML-Klammer-
 * Erkennung kennt nur Tags).
 */
class FluidBraceHighlighterFactory : HighlightUsagesHandlerFactoryBase() {
    override fun createHighlightUsagesHandler(editor: Editor, file: PsiFile, target: PsiElement): HighlightUsagesHandlerBase<*>? {
        if (FluidInline.otherFluidPluginActive() || !FluidInline.isFluidFile(file)) return null
        val text = file.viewProvider.contents
        val caret = editor.caretModel.offset
        // Klammer direkt hinter oder vor dem Cursor
        val at = listOf(caret, caret - 1).firstOrNull { it in text.indices && text[it] in "{}()" } ?: return null
        val pair = matching(text, at) ?: return null
        return object : HighlightUsagesHandlerBase<PsiElement>(editor, file) {
            override fun getTargets(): List<PsiElement> = listOf(target)
            override fun selectTargets(targets: List<PsiElement>, selectionConsumer: Consumer<in List<PsiElement>>) = selectionConsumer.consume(targets)
            override fun computeUsages(targets: List<PsiElement>) {
                myReadUsages += TextRange(pair.first, pair.first + 1)
                myReadUsages += TextRange(pair.second, pair.second + 1)
            }
        }
    }

    /** Offsets der öffnenden und schließenden Klammer zu [at], wenn sie in einem Fluid-Ausdruck liegt. */
    private fun matching(text: CharSequence, at: Int): Pair<Int, Int>? {
        val from = maxOf(0, at - 5000)
        val to = minOf(text.length, at + 5000)
        val e = FluidInline.expressions(text, from, to).firstOrNull { at >= it.start && at < it.end } ?: return null
        val open = text[at] == '{' || text[at] == '('
        val openChar = if (text[at] == '{' || text[at] == '}') '{' else '('
        val closeChar = if (openChar == '{') '}' else ')'
        // Klammern im Ausdruck paaren (ohne Strings)
        val stack = ArrayList<Int>()
        var quote: Char? = null
        var i = e.start
        while (i < e.end) {
            val c = text[i]
            if (quote != null) { if (c == '\\') i++ else if (c == quote) quote = null }
            else when (c) {
                '\'', '"' -> quote = c
                openChar -> stack += i
                closeChar -> if (stack.isNotEmpty()) {
                    val o = stack.removeAt(stack.size - 1)
                    if (open && o == at || !open && i == at) return o to i
                }
            }
            i++
        }
        return null
    }
}
