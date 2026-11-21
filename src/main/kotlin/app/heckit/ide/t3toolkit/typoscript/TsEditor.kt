package app.heckit.ide.t3toolkit.typoscript

import com.intellij.lang.BracePair
import com.intellij.lang.CodeDocumentationAwareCommenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as D
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import javax.swing.Icon

object TsColors {
    val PATH = createTextAttributesKey("T3TS_PATH", D.IDENTIFIER)
    val DOT = createTextAttributesKey("T3TS_DOT", D.DOT)
    val OPERATOR = createTextAttributesKey("T3TS_OPERATOR", D.OPERATION_SIGN)
    val VALUE = createTextAttributesKey("T3TS_VALUE", D.STRING)
    val OBJECT_TYPE = createTextAttributesKey("T3TS_OBJECT_TYPE", D.KEYWORD)
    val CONSTANT = createTextAttributesKey("T3TS_CONSTANT", D.CONSTANT)
    val COMMENT = createTextAttributesKey("T3TS_COMMENT", D.LINE_COMMENT)
    val BLOCK_COMMENT = createTextAttributesKey("T3TS_BLOCK_COMMENT", D.BLOCK_COMMENT)
    val BRACES = createTextAttributesKey("T3TS_BRACES", D.BRACES)
    val PARENTHESES = createTextAttributesKey("T3TS_PARENTHESES", D.PARENTHESES)
    val CONDITION = createTextAttributesKey("T3TS_CONDITION", D.METADATA)
    val CONDITION_BRACKETS = createTextAttributesKey("T3TS_CONDITION_BRACKETS", D.BRACKETS)
    val KEYWORD = createTextAttributesKey("T3TS_KEYWORD", D.KEYWORD)
    val IMPORT_PATH = createTextAttributesKey("T3TS_IMPORT_PATH", D.STRING)
    val FUNCTION = createTextAttributesKey("T3TS_FUNCTION", D.FUNCTION_CALL)
    val PROPERTY = createTextAttributesKey("T3TS_PROPERTY", D.INSTANCE_FIELD)
    val NUMBER = createTextAttributesKey("T3TS_NUMBER", D.NUMBER)
    val BAD = createTextAttributesKey("T3TS_BAD", HighlighterColors.BAD_CHARACTER)
}

class TsSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer() = TsLexer()
    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = pack(
        when (tokenType) {
            TsTokens.IDENTIFIER -> TsColors.PATH
            TsTokens.DOT -> TsColors.DOT
            TsTokens.OP_ASSIGN, TsTokens.OP_COPY, TsTokens.OP_REFERENCE, TsTokens.OP_UNSET, TsTokens.OP_MODIFY -> TsColors.OPERATOR
            TsTokens.VALUE -> TsColors.VALUE
            TsTokens.OBJECT_TYPE -> TsColors.OBJECT_TYPE
            TsTokens.CONSTANT -> TsColors.CONSTANT
            TsTokens.COMMENT -> TsColors.COMMENT
            TsTokens.BLOCK_COMMENT -> TsColors.BLOCK_COMMENT
            TsTokens.LBRACE, TsTokens.RBRACE -> TsColors.BRACES
            TsTokens.MULTILINE_START, TsTokens.MULTILINE_END, TsTokens.LPAREN, TsTokens.RPAREN -> TsColors.PARENTHESES
            TsTokens.CONDITION_BODY -> TsColors.CONDITION
            TsTokens.CONDITION_START, TsTokens.CONDITION_END -> TsColors.CONDITION_BRACKETS
            TsTokens.CONDITION_KEYWORD, TsTokens.IMPORT_KEYWORD -> TsColors.KEYWORD
            TsTokens.IMPORT_PATH -> TsColors.IMPORT_PATH
            TsTokens.INCLUDE -> TsColors.CONDITION
            TsTokens.FUNCTION_NAME -> TsColors.FUNCTION
            TokenType.BAD_CHARACTER -> TsColors.BAD
            else -> null
        },
    )
}

class TsSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = TsSyntaxHighlighter()
}

class TsColorSettingsPage : ColorSettingsPage {
    private val descriptors = arrayOf(
        AttributesDescriptor("Object path", TsColors.PATH),
        AttributesDescriptor("Known property", TsColors.PROPERTY),
        AttributesDescriptor("Numeric key", TsColors.NUMBER),
        AttributesDescriptor("Dot", TsColors.DOT),
        AttributesDescriptor("Operator", TsColors.OPERATOR),
        AttributesDescriptor("Value", TsColors.VALUE),
        AttributesDescriptor("Object type (TEXT, COA …)", TsColors.OBJECT_TYPE),
        AttributesDescriptor("Constant", TsColors.CONSTANT),
        AttributesDescriptor("Comment//Line comment", TsColors.COMMENT),
        AttributesDescriptor("Comment//Block comment", TsColors.BLOCK_COMMENT),
        AttributesDescriptor("Braces", TsColors.BRACES),
        AttributesDescriptor("Parentheses", TsColors.PARENTHESES),
        AttributesDescriptor("Condition", TsColors.CONDITION),
        AttributesDescriptor("Condition brackets", TsColors.CONDITION_BRACKETS),
        AttributesDescriptor("Keyword (@import, [END] …)", TsColors.KEYWORD),
        AttributesDescriptor("Import path", TsColors.IMPORT_PATH),
        AttributesDescriptor("Function (:=)", TsColors.FUNCTION),
    )

    override fun getIcon(): Icon = TsIcons.FILE
    override fun getHighlighter(): SyntaxHighlighter = TsSyntaxHighlighter()
    override fun getDemoText() = """
        # Page setup
        @import 'EXT:my_site/Configuration/TypoScript/Helper/*.typoscript'

        page = PAGE
        page {
          <prop>typeNum</prop> = 0
          <num>10</num> = FLUIDTEMPLATE
          <num>10</num> {
            <prop>templateName</prop> = Default
            <prop>templateRootPaths</prop>.<num>10</num> = {${'$'}site.templates}
            <prop>dataProcessing</prop>.<num>10</num> = menu
          }
          <prop>bodyTagCDATA</prop> >
        }

        /* Footer */
        lib.footer < lib.content
        lib.classes := addToList(footer)
        lib.text (
          Multi-line
          value
        )

        [page["doktype"] == 104]
          page.<num>10</num>.<prop>templateName</prop> = Booking
        [END]
    """.trimIndent()
    override fun getAdditionalHighlightingTagToDescriptorMap() = mapOf("prop" to TsColors.PROPERTY, "num" to TsColors.NUMBER)
    override fun getAttributeDescriptors() = descriptors
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getDisplayName() = "TypoScript"
}

class TsCommenter : CodeDocumentationAwareCommenter {
    override fun getLineCommentPrefix() = "# "
    override fun getBlockCommentPrefix() = "/*"
    override fun getBlockCommentSuffix() = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
    override fun getLineCommentTokenType(): IElementType = TsTokens.COMMENT
    override fun getBlockCommentTokenType(): IElementType = TsTokens.BLOCK_COMMENT
    override fun getDocumentationCommentTokenType(): IElementType? = null
    override fun getDocumentationCommentPrefix(): String? = null
    override fun getDocumentationCommentLinePrefix(): String? = null
    override fun getDocumentationCommentSuffix(): String? = null
    override fun isDocumentationComment(element: PsiComment?) = false
}

class TsBraceMatcher : PairedBraceMatcher {
    override fun getPairs() = PAIRS
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?) = true
    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int) = openingBraceOffset

    private companion object {
        val PAIRS = arrayOf(
            BracePair(TsTokens.LBRACE, TsTokens.RBRACE, true),
            BracePair(TsTokens.MULTILINE_START, TsTokens.MULTILINE_END, false),
            BracePair(TsTokens.LPAREN, TsTokens.RPAREN, false),
            BracePair(TsTokens.CONDITION_START, TsTokens.CONDITION_END, false),
        )
    }
}

/** Blöcke, mehrzeilige Werte, Block-Kommentare und Bedingungsbereiche bis `[END]`/`[GLOBAL]` bzw. zur nächsten Bedingung. */
class TsFoldingBuilder : FoldingBuilderEx() {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val result = ArrayList<FoldingDescriptor>()
        fun multiLine(r: TextRange) = document.getLineNumber(r.startOffset) != document.getLineNumber(r.endOffset)
        PsiTreeUtil.processElements(root) { e ->
            when {
                e is TsBlock -> {
                    val open = e.node.findChildByType(TsTokens.LBRACE)
                    val close = e.node.findChildByType(TsTokens.RBRACE)
                    if (open != null && close != null) {
                        val r = TextRange(open.startOffset, close.textRange.endOffset)
                        if (multiLine(r)) result += FoldingDescriptor(e.node, r, null, "{...}")
                    }
                }
                e is TsStatement && e.kind == TsElements.MULTILINE_ASSIGNMENT -> {
                    val open = e.node.findChildByType(TsTokens.MULTILINE_START)
                    val close = e.node.findChildByType(TsTokens.MULTILINE_END)
                    if (open != null && close != null) {
                        val r = TextRange(open.startOffset, close.textRange.endOffset)
                        if (multiLine(r)) result += FoldingDescriptor(e.node, r, null, "(...)")
                    }
                }
                e is PsiComment && e.node.elementType == TsTokens.BLOCK_COMMENT && multiLine(e.textRange) ->
                    result += FoldingDescriptor(e.node, e.textRange, null, "/*...*/")
            }
            true
        }
        // Bedingungsbereiche (nur oberste Ebene)
        val top = (root as? TsFile)?.statements.orEmpty()
        top.forEachIndexed { i, s ->
            if (s !is TsCondition || s.isTerminator) return@forEachIndexed
            val next = top.drop(i + 1).firstOrNull { it is TsCondition }
            val lastInside = (if (next != null) top.subList(i + 1, top.indexOf(next)) else top.drop(i + 1)).lastOrNull() ?: return@forEachIndexed
            val end = if (next is TsCondition && next.isTerminator) next.textRange.endOffset else lastInside.textRange.endOffset
            val r = TextRange(s.textRange.endOffset, end)
            if (multiLine(r)) result += FoldingDescriptor(s.node, r, null, " ...")
        }
        return result.toTypedArray()
    }

    override fun getPlaceholderText(node: com.intellij.lang.ASTNode) = "..."
    override fun isCollapsedByDefault(node: com.intellij.lang.ASTNode) = false
}

class TsBreadcrumbsProvider : BreadcrumbsProvider {
    override fun getLanguages() = arrayOf(TsLanguage)
    override fun acceptElement(element: PsiElement) = element is TsBlock || element is TsCondition
    override fun getElementInfo(element: PsiElement): String = when (element) {
        is TsBlock -> element.path?.text.orEmpty()
        is TsCondition -> element.text.trim().take(40)
        else -> ""
    }
    override fun getElementTooltip(element: PsiElement): String? = (element as? TsBlock)?.fullPath()
}
