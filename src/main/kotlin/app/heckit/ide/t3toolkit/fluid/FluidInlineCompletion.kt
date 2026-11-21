package app.heckit.ide.t3toolkit.fluid

import app.heckit.ide.t3toolkit.typoscript.TsIndex
import app.heckit.ide.t3toolkit.typoscript.TsKind
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlText

/**
 * Vervollständigung in Fluid-Inline-Ausdrücken: `{f:` bzw. `-> f:` → ViewHelper, in `f:name(` die Argumente,
 * sonst Variablen (aus der Datei, Component-Parametern, `f:render`-Aufrufen, Extbase-Actions und TypoScript).
 */
class FluidInlineCompletion : CompletionContributor() {
    private companion object {
        val VIEWHELPER_TYPED = Regex("""(?:^|->\s*|[\s(,:!\[{])([A-Za-z0-9_]+):([A-Za-z0-9_.]*)$""")
        val CALL = Regex("""([A-Za-z0-9_]+):([A-Za-z0-9_.]+)\s*$""")
        val ARG_KEY = Regex("""(?:^|[(,])\s*([A-Za-z0-9_]*)$""")
        val VARIABLE_TYPED = Regex("""(?:^|->\s*|[\s(,:!\[{=<>&|?%+*/-])([A-Za-z_][A-Za-z0-9_]*)?$""")
        val PRESENT_ARG = Regex("""(?:^|[(,])\s*([A-Za-z0-9_]+)\s*:""")
    }

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC) return
        if (FluidInline.otherFluidPluginActive()) return
        val file = parameters.originalFile
        if (!FluidInline.isFluidFile(file)) return
        val position = parameters.position
        val host = PsiTreeUtil.getParentOfType(position, XmlText::class.java, XmlAttributeValue::class.java) ?: return
        val text = file.viewProvider.contents
        val offset = parameters.offset
        val open = FluidInline.openAt(text, host.textRange.startOffset, offset) ?: return
        val expr = text.subSequence(open + 1, offset).toString()

        // 1. ViewHelper-Name
        VIEWHELPER_TYPED.find(expr)?.let { m ->
            val prefix = m.groupValues[1]
            val viewHelpers = ViewHelpers.all(file, prefix)
            if (viewHelpers.isNotEmpty()) {
                val matcher = result.withPrefixMatcher("$prefix:${m.groupValues[2]}")
                viewHelpers.forEach { (name, cls) ->
                    val required = ViewHelpers.arguments(cls).filter { it.required }.map { it.name }
                    matcher.addElement(
                        LookupElementBuilder.create("$prefix:$name").withIcon(AllIcons.Nodes.Function)
                            .withTailText("(" + required.joinToString(", ") { "$it: " } + ")", true)
                            .withTypeText(cls.namespaceName.trim('\\').substringAfterLast('\\').ifEmpty { null })
                            .withInsertHandler { ctx, _ ->
                                val doc = ctx.document
                                if (doc.charsSequence.getOrNull(ctx.tailOffset) != '(') {
                                    val args = required.joinToString(", ") { "$it: " }
                                    doc.insertString(ctx.tailOffset, "($args)")
                                    ctx.editor.caretModel.moveToOffset(ctx.tailOffset + 1 + (required.firstOrNull()?.let { it.length + 2 } ?: 0))
                                }
                                if (required.isEmpty()) AutoPopupController.getInstance(ctx.project).scheduleAutoPopup(ctx.editor)
                            },
                    )
                }
                matcher.stopHere()
                return
            }
        }

        // 2. Argument im innersten offenen Aufruf
        innermostCall(expr)?.let { (callStart, argsText) ->
            val keyTyped = ARG_KEY.find(argsText)
            val call = CALL.find(expr.substring(0, callStart))
            if (keyTyped != null && call != null) {
                val cls = ViewHelpers.resolve(file, call.groupValues[1], call.groupValues[2]).firstOrNull()
                if (cls != null) {
                    val present = PRESENT_ARG.findAll(argsText).map { it.groupValues[1] }.toSet()
                    val matcher = result.withPrefixMatcher(keyTyped.groupValues[1])
                    ViewHelpers.arguments(cls).filter { it.name !in present }.forEach { a ->
                        matcher.addElement(
                            PrioritizedLookupElement.withPriority(
                                LookupElementBuilder.create(a.name).withIcon(AllIcons.Nodes.Parameter).withBoldness(a.required)
                                    .withTypeText(a.type).withTailText(a.description?.let { "  " + it.take(60) }, true)
                                    .withInsertHandler { ctx, _ ->
                                        if (ctx.document.charsSequence.getOrNull(ctx.tailOffset) != ':') {
                                            ctx.document.insertString(ctx.tailOffset, ": ")
                                            ctx.editor.caretModel.moveToOffset(ctx.tailOffset + 2)
                                        }
                                    },
                                if (a.required) 1.0 else 0.0,
                            ),
                        )
                    }
                    matcher.stopHere()
                    return
                }
            }
        }

        // 3. Variable (nicht nach einem Punkt)
        val v = VARIABLE_TYPED.find(expr) ?: return
        val typed = v.groupValues[1]
        if (expr.length > typed.length && expr[expr.length - typed.length - 1] == '.') return
        val matcher = result.withPrefixMatcher(typed)
        FluidVariables.namesFor(file, offset).forEach { (name, source) ->
            matcher.addElement(LookupElementBuilder.create(name).withIcon(AllIcons.Nodes.Variable).withTypeText(source))
        }
        // Präfixe für ViewHelper direkt nach {
        if (expr == typed) ViewHelpers.namespacesFor(file).keys.forEach { prefix ->
            if (ViewHelpers.all(file, prefix).isEmpty()) return@forEach
            matcher.addElement(
                LookupElementBuilder.create("$prefix:").withIcon(AllIcons.Nodes.Package).withTypeText("ViewHelpers")
                    .withInsertHandler { ctx, _ -> AutoPopupController.getInstance(ctx.project).scheduleAutoPopup(ctx.editor) },
            )
        }
        matcher.stopHere()
    }

    /** Anfang des innersten offenen `(` in [expr] und der Text dahinter (ohne Strings). */
    private fun innermostCall(expr: String): Pair<Int, String>? {
        val stack = ArrayList<Int>()
        var quote: Char? = null
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            if (quote != null) { if (c == '\\') i++ else if (c == quote) quote = null }
            else when (c) {
                '\'', '"' -> quote = c
                '(' -> stack += i
                ')' -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                '{', '}' -> {}
            }
            i++
        }
        if (quote != null) return null
        val start = stack.lastOrNull() ?: return null
        // Argumentwert in einem verschachtelten Array {…} zählt nicht als Schlüssel-Position
        val after = expr.substring(start + 1)
        if (after.count { it == '{' } > after.count { it == '}' }) return null
        return start to after
    }
}

/** Nach `{` bzw. `:` in Fluid-Ausdrücken die Liste öffnen. */
class FluidInlineTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped != ':' && charTyped != '(') return Result.CONTINUE
        if (FluidInline.otherFluidPluginActive() || !FluidInline.isFluidFile(file)) return Result.CONTINUE
        val offset = editor.caretModel.offset
        val text = editor.document.charsSequence
        val before = text.subSequence(maxOf(0, offset - 40), offset).toString()
        // {f: bzw. -> f: bzw. f:name(
        val fire = when (charTyped) {
            ':' -> Regex("""(?:\{|->\s*)[A-Za-z0-9_]+$""").containsMatchIn(before)
            else -> Regex("""[A-Za-z0-9_]+:[A-Za-z0-9_.]+$""").containsMatchIn(before)
        }
        if (!fire) return Result.CONTINUE
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.STOP
    }
}

internal fun tsTemplateVariables(project: Project): List<Pair<String, String>> {
    val result = LinkedHashMap<String, String>()
    for (path in TsIndex.allPaths(project, TsKind.SETUP)) {
        val segs = path.split('.')
        val vi = segs.indexOf("variables")
        if (vi >= 0 && vi + 1 < segs.size && vi + 2 == segs.size) result.putIfAbsent(segs[vi + 1], "TypoScript variables")
        if (segs.size >= 3 && segs.last() == "as" && "dataProcessing" in segs) {
            TsIndex.definitions(project, TsKind.SETUP, path).firstOrNull()?.def?.value?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { result.putIfAbsent(it, "dataProcessing") }
        }
    }
    return result.toList()
}
