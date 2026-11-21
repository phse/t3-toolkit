package app.heckit.ide.t3toolkit.typoscript

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class TsParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?) = TsLexer()
    override fun createParser(project: Project?): PsiParser = TsParser()
    override fun getFileNodeType(): IFileElementType = FILE
    override fun getCommentTokens(): TokenSet = TsTokens.COMMENTS
    override fun getStringLiteralElements(): TokenSet = TsTokens.STRINGS
    override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
        TsElements.OBJECT_PATH -> TsObjectPath(node)
        TsElements.VALUE -> TsValue(node)
        TsElements.BLOCK -> TsBlock(node)
        TsElements.CONDITION -> TsCondition(node)
        TsElements.IMPORT -> TsImport(node)
        TsElements.INCLUDE -> TsInclude(node)
        else -> TsStatement(node)
    }
    override fun createFile(viewProvider: FileViewProvider): PsiFile = TsFile(viewProvider)

    companion object {
        @JvmField val FILE = IFileElementType(TsLanguage)
    }
}

/**
 * Baut aus den Zeilen-Tokens Anweisungen: Zuweisung, Kopie, Referenz, Löschen, Funktion, mehrzeiliger Wert, Block
 * (mit verschachtelten Anweisungen), Bedingung, `@import` und `<INCLUDE_TYPOSCRIPT>`.
 */
class TsParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val file = builder.mark()
        while (!builder.eof()) statement(builder, 0)
        file.done(root)
        return builder.treeBuilt
    }

    private fun statement(b: PsiBuilder, depth: Int) {
        when (b.tokenType) {
            TsTokens.IDENTIFIER, TsTokens.DOT -> pathStatement(b, depth)
            TsTokens.CONDITION_START -> condition(b)
            TsTokens.IMPORT_KEYWORD -> {
                val m = b.mark()
                b.advanceLexer()
                if (b.tokenType == TsTokens.IMPORT_PATH) b.advanceLexer() else b.error("Quoted file or folder expected")
                m.done(TsElements.IMPORT)
            }
            TsTokens.INCLUDE -> {
                val m = b.mark()
                b.advanceLexer()
                m.done(TsElements.INCLUDE)
            }
            TsTokens.RBRACE -> {
                val m = b.mark()
                b.advanceLexer()
                m.error("Unexpected '}': no block to close")
            }
            else -> {
                val m = b.mark()
                b.advanceLexer()
                m.error("Unexpected text")
            }
        }
    }

    /** Ein Pfad endet am ersten Leerraum - die Tokens der nächsten Zeile gehören nicht mehr dazu. */
    private fun path(b: PsiBuilder) {
        val m = b.mark()
        b.advanceLexer()
        while ((b.tokenType == TsTokens.IDENTIFIER || b.tokenType == TsTokens.DOT) && b.rawLookup(-1) in TsTokens.PATH_PARTS) b.advanceLexer()
        m.done(TsElements.OBJECT_PATH)
    }

    private fun value(b: PsiBuilder) {
        if (b.tokenType !in TsTokens.VALUES) return
        val m = b.mark()
        while (b.tokenType in TsTokens.VALUES) b.advanceLexer()
        m.done(TsElements.VALUE)
    }

    private fun pathStatement(b: PsiBuilder, depth: Int) {
        val m = b.mark()
        path(b)
        when (b.tokenType) {
            TsTokens.OP_ASSIGN -> {
                b.advanceLexer()
                value(b)
                m.done(TsElements.ASSIGNMENT)
            }
            TsTokens.OP_COPY, TsTokens.OP_REFERENCE -> {
                val type = if (b.tokenType == TsTokens.OP_COPY) TsElements.COPY else TsElements.REFERENCE
                b.advanceLexer()
                if (b.tokenType == TsTokens.IDENTIFIER || b.tokenType == TsTokens.DOT) path(b) else b.error("Object path expected")
                m.done(type)
            }
            TsTokens.OP_UNSET -> {
                b.advanceLexer()
                m.done(TsElements.UNSET)
            }
            TsTokens.OP_MODIFY -> {
                b.advanceLexer()
                if (b.tokenType == TsTokens.FUNCTION_NAME) b.advanceLexer() else b.error("Function name expected")
                if (b.tokenType == TsTokens.LPAREN) {
                    b.advanceLexer()
                    value(b)
                    if (b.tokenType == TsTokens.RPAREN) b.advanceLexer() else b.error("')' expected")
                } else {
                    b.error("'(' expected")
                }
                m.done(TsElements.MODIFICATION)
            }
            TsTokens.MULTILINE_START -> {
                b.advanceLexer()
                value(b)
                if (b.tokenType == TsTokens.MULTILINE_END) b.advanceLexer() else b.error("Missing ')': no line starting with ')' closes the value")
                m.done(TsElements.MULTILINE_ASSIGNMENT)
            }
            TsTokens.LBRACE -> {
                b.advanceLexer()
                while (!b.eof() && b.tokenType != TsTokens.RBRACE) {
                    if (b.tokenType == TsTokens.CONDITION_START) break
                    statement(b, depth + 1)
                }
                if (b.tokenType == TsTokens.RBRACE) b.advanceLexer()
                m.done(TsElements.BLOCK)
            }
            else -> {
                b.error("Operator expected (=, <, =<, >, :=, {, ()")
                m.done(TsElements.INCOMPLETE)
            }
        }
    }

    private fun condition(b: PsiBuilder) {
        val m = b.mark()
        b.advanceLexer()
        if (b.tokenType == TsTokens.CONDITION_KEYWORD || b.tokenType == TsTokens.CONDITION_BODY) b.advanceLexer()
        if (b.tokenType == TsTokens.CONDITION_END) b.advanceLexer() else b.error("']' expected")
        m.done(TsElements.CONDITION)
    }
}
