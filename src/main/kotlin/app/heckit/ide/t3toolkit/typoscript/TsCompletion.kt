package app.heckit.ide.t3toolkit.typoscript

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionInitializationContext
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/**
 * TypoScript-Vervollständigung: Pfadsegmente (Eigenschaften aus der Referenz nach Objekttyp plus im Projekt gesetzte Schlüssel),
 * Werte (Objekttypen, Datenprozessoren, bekannte Werte), Konstanten `{$…}`, Funktionen nach `:=`, Bedingungen und `EXT:`-Pfade.
 */
class TsCompletionContributor : CompletionContributor() {
    override fun beforeCompletion(context: CompletionInitializationContext) {
        if (context.file is TsFile) context.dummyIdentifier = CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED
    }

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC) return
        val file = parameters.originalFile as? TsFile ?: return
        val position = parameters.position
        val project = file.project
        val kind = TsKind.of(file)
        val offset = parameters.offset
        val inToken = (offset - position.textRange.startOffset).coerceIn(0, position.textLength)
        val typed = position.text.substring(0, inToken)
        val lineBefore = parameters.editor.document.charsSequence.let { t ->
            t.subSequence(t.lastIndexOf('\n', offset - 1).let { if (it < 0) 0 else it + 1 }, offset).toString()
        }

        when (position.node.elementType) {
            TsTokens.IDENTIFIER -> {
                val path = position.parent as? TsObjectPath ?: return
                completePath(project, kind, path, position, typed, result)
            }
            TsTokens.VALUE, TsTokens.OBJECT_TYPE, TsTokens.CONSTANT -> {
                // {$… → Konstanten
                val constStart = lineBefore.lastIndexOf("{$")
                if (constStart >= 0 && lineBefore.indexOf('}', constStart) < 0) {
                    completeConstants(project, lineBefore.substring(constStart + 2), result)
                    return
                }
                if (ExtPathCompletion.complete(project, lineBefore, result)) return
                val statement = PsiTreeUtil.getParentOfType(position, TsStatement::class.java) ?: return
                if (statement.kind == TsElements.ASSIGNMENT) completeValue(project, kind, statement, typed.trimStart(), result)
            }
            TsTokens.FUNCTION_NAME -> {
                val matcher = result.withPrefixMatcher(typed.trimStart())
                TsRef.functions.forEach { (name, desc) ->
                    matcher.addElement(LookupElementBuilder.create(name).withIcon(AllIcons.Nodes.Function).withTailText("()  $desc", true)
                        .withInsertHandler { ctx, _ ->
                            val doc = ctx.document
                            if (doc.charsSequence.getOrNull(ctx.tailOffset) != '(') {
                                doc.insertString(ctx.tailOffset, "()")
                                ctx.editor.caretModel.moveToOffset(ctx.tailOffset - 1)
                            }
                        })
                }
            }
            TsTokens.CONDITION_BODY -> {
                val word = Regex("""[A-Za-z_]*$""").find(lineBefore)?.value.orEmpty()
                val matcher = result.withPrefixMatcher(word)
                TsRef.conditionWords.forEach { (name, desc) ->
                    matcher.addElement(LookupElementBuilder.create(name).withIcon(AllIcons.Nodes.Variable).withTailText("  $desc", true))
                }
                matcher.stopHere()
            }
            TsTokens.IMPORT_PATH -> {
                if (!ExtPathCompletion.complete(project, lineBefore, result, setOf("typoscript", "tsconfig", "txt"))) {
                    // Noch kein EXT: - anbieten
                    if (lineBefore.trimEnd().endsWith("'") || lineBefore.trimEnd().endsWith("\"")) ExtPathCompletion.complete(project, "EXT:", result)
                }
            }
            TsTokens.INCLUDE -> ExtPathCompletion.complete(project, lineBefore, result, setOf("typoscript", "tsconfig", "txt"))
        }
    }

    private fun completePath(project: Project, kind: TsKind, path: TsObjectPath, segment: com.intellij.psi.PsiElement, typed: String, result: CompletionResultSet) {
        val statement = path.parent as? TsStatement ?: return
        val index = path.segmentElements.indexOf(segment)
        if (index < 0) return
        val before = path.segments.take(index)
        val parent = when {
            !path.isSource -> statement.prefixSegments() + before
            path.isRelative -> statement.prefixSegments() + before
            else -> before
        }
        val matcher = result.withPrefixMatcher(typed)
        val added = HashSet<String>()
        val type = TsTypes.typeOf(project, parent, kind)
        TsRef.props(type).forEach { p ->
            if (p.name == "#" || p.name.endsWith("*")) return@forEach
            if (!added.add(p.name)) return@forEach
            matcher.addElement(
                PrioritizedLookupElement.withPriority(
                    LookupElementBuilder.create(p.name).withIcon(AllIcons.Nodes.Property).withBoldness(p.owner == type)
                        .withTypeText(p.childType?.takeIf { it != "stdWrap" || p.valueType == "stdWrap" }?.let(::displayType) ?: p.valueType)
                        .withTailText(if (p.description.isEmpty()) null else "  " + p.description.take(70), true),
                    if (p.owner == type) 2.0 else 1.0,
                ),
            )
        }
        TsIndex.children(project, kind, parent).forEach { child ->
            if (child.endsWith(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED) || !added.add(child)) return@forEach
            matcher.addElement(
                PrioritizedLookupElement.withPriority(
                    LookupElementBuilder.create(child).withIcon(AllIcons.Nodes.Field).withTypeText("in project"),
                    if (child.all { it.isDigit() }) 0.5 else 1.5,
                ),
            )
        }
    }

    private fun displayType(t: String) = t.removePrefix("DP:").let { if (it == "ANY") "array" else it }

    private fun completeValue(project: Project, kind: TsKind, statement: TsStatement, typed: String, result: CompletionResultSet) {
        val segments = statement.fullSegments()
        if (segments.isEmpty()) return
        val parentSegments = segments.dropLast(1)
        val parentType = TsTypes.typeOf(project, parentSegments, kind)
        val prop = TsRef.prop(parentType, segments.last())
        val matcher = result.withPrefixMatcher(typed)
        // Datenprozessoren
        if (parentSegments.lastOrNull() == "dataProcessing") {
            TsRef.dataProcessors.forEach { (alias, cls) ->
                matcher.addElement(LookupElementBuilder.create(alias).withIcon(AllIcons.Nodes.Class).withTypeText(cls.substringAfterLast('\\')))
                matcher.addElement(LookupElementBuilder.create(cls).withIcon(AllIcons.Nodes.Class).withTypeText("class"))
            }
            return
        }
        val child = prop?.childType
        // Objekttypen
        val objectTypes = when {
            child == "cObject" -> TsRef.contentObjects.keys
            child == "MENUOBJ" -> listOf("TMENU")
            child != null && TsRef.isRoot(parentType) && child in TsRef.types && child.uppercase() == child -> listOf(child)
            parentType == null && segments.size >= 2 -> TsRef.contentObjects.keys
            else -> emptyList()
        }
        objectTypes.forEach { t ->
            matcher.addElement(LookupElementBuilder.create(t).withIcon(AllIcons.Nodes.Type).withBoldness(true)
                .withTailText(TsRef.contentObjects[t]?.let { "  $it" }, true))
        }
        prop?.values?.forEach { matcher.addElement(LookupElementBuilder.create(it).withIcon(AllIcons.Nodes.Enum)) }
        if (prop?.valueType == "boolean") listOf("0", "1").forEach { matcher.addElement(LookupElementBuilder.create(it)) }
    }

    private fun completeConstants(project: Project, typed: String, result: CompletionResultSet) {
        val matcher = result.withPrefixMatcher(typed)
        val added = HashSet<String>()
        fun add(name: String, type: String, tail: String?) {
            if (!added.add(name) || name.endsWith(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED)) return
            matcher.addElement(LookupElementBuilder.create(name).withIcon(AllIcons.Nodes.Constant).withTypeText(type)
                .withTailText(tail?.let { "  = " + it.take(50) }, true)
                .withInsertHandler { ctx, _ ->
                    val doc = ctx.document
                    if (doc.charsSequence.getOrNull(ctx.tailOffset) != '}') doc.insertString(ctx.tailOffset, "}")
                    ctx.editor.caretModel.moveToOffset(ctx.tailOffset + 1)
                })
        }
        TsSiteSettings.all(project).forEach { (name, list) ->
            val def = list.firstOrNull { it.isDefinition } ?: list.first()
            add(name, if (def.isDefinition) "site setting" else "settings.yaml", def.default)
        }
        TsIndex.allPaths(project, TsKind.CONSTANTS).forEach { path ->
            if (path in added || !TsIndex.exists(project, TsKind.CONSTANTS, path)) return@forEach
            val def = TsIndex.definitions(project, TsKind.CONSTANTS, path).firstOrNull { it.def.op == TsDef.ASSIGN }
            if (def != null) add(path, "constant", def.def.value)
        }
        result.stopHere()
    }
}

/** Nach `{$`, `.` in Pfaden, `EXT:`-Ordnern und `:=` die Liste gleich öffnen. */
class TsTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file !is TsFile) return Result.CONTINUE
        val offset = editor.caretModel.offset
        val text = editor.document.charsSequence
        val line = text.subSequence(text.lastIndexOf('\n', offset - 1).let { if (it < 0) 0 else it + 1 }, offset).toString()
        val popup = when (charTyped) {
            '$' -> line.endsWith("{")
            // Nur im Pfad links vom Operator
            '.' -> line.isNotBlank() && line.trimStart().none { it in "=<>([#/ \t" }
            '/' -> "EXT:" in line
            else -> false
        }
        if (popup) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
            return Result.STOP
        }
        return Result.CONTINUE
    }
}
