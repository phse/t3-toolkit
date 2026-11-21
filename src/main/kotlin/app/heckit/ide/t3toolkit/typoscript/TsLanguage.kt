package app.heckit.ide.t3toolkit.typoscript

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import javax.swing.Icon

/** TypoScript (Setup, Constants, TSconfig). Eigene ID, damit es neben anderen TypoScript-Plugins keine Kollision gibt. */
object TsLanguage : Language("T3Toolkit.TypoScript") {
    private fun readResolve(): Any = TsLanguage
    override fun getDisplayName() = "TypoScript"
    override fun isCaseSensitive() = true
}

object TsIcons {
    @JvmField val FILE: Icon = IconLoader.getIcon("/icons/typoscript.svg", TsIcons::class.java)
}

class TsFileType private constructor() : LanguageFileType(TsLanguage) {
    override fun getName() = "T3 TypoScript"
    override fun getDisplayName() = "TypoScript"
    override fun getDescription() = "TypoScript and TSconfig (T3 Toolkit)"
    override fun getDefaultExtension() = "typoscript"
    override fun getIcon(): Icon = TsIcons.FILE

    companion object {
        @JvmField val INSTANCE = TsFileType()
    }
}

class TsTokenType(debugName: String) : IElementType(debugName, TsLanguage) {
    override fun toString() = "Ts:" + super.toString()
}

class TsElementType(debugName: String) : IElementType(debugName, TsLanguage)

object TsTokens {
    @JvmField val COMMENT = TsTokenType("COMMENT")
    @JvmField val BLOCK_COMMENT = TsTokenType("BLOCK_COMMENT")
    /** Ein Pfadsegment links vom Operator (`page`, `10`, `templateName`). */
    @JvmField val IDENTIFIER = TsTokenType("IDENTIFIER")
    @JvmField val DOT = TsTokenType("DOT")
    @JvmField val OP_ASSIGN = TsTokenType("=")
    @JvmField val OP_COPY = TsTokenType("<")
    @JvmField val OP_REFERENCE = TsTokenType("=<")
    @JvmField val OP_UNSET = TsTokenType(">")
    @JvmField val OP_MODIFY = TsTokenType(":=")
    @JvmField val MULTILINE_START = TsTokenType("(")
    @JvmField val MULTILINE_END = TsTokenType(")")
    @JvmField val LBRACE = TsTokenType("{")
    @JvmField val RBRACE = TsTokenType("}")
    /** Wert rechts vom `=` (ohne Konstanten). */
    @JvmField val VALUE = TsTokenType("VALUE")
    /** Ein Wert, der nur aus einem Objekttyp besteht (`TEXT`, `COA`, `FLUIDTEMPLATE` …). */
    @JvmField val OBJECT_TYPE = TsTokenType("OBJECT_TYPE")
    /** `{$some.constant}` */
    @JvmField val CONSTANT = TsTokenType("CONSTANT")
    @JvmField val FUNCTION_NAME = TsTokenType("FUNCTION_NAME")
    @JvmField val LPAREN = TsTokenType("FUNCTION_(")
    @JvmField val RPAREN = TsTokenType("FUNCTION_)")
    @JvmField val CONDITION_START = TsTokenType("[")
    @JvmField val CONDITION_END = TsTokenType("]")
    /** `ELSE`, `END`, `GLOBAL` */
    @JvmField val CONDITION_KEYWORD = TsTokenType("CONDITION_KEYWORD")
    @JvmField val CONDITION_BODY = TsTokenType("CONDITION_BODY")
    @JvmField val IMPORT_KEYWORD = TsTokenType("@import")
    /** Pfad samt Anführungszeichen. */
    @JvmField val IMPORT_PATH = TsTokenType("IMPORT_PATH")
    /** Eine ganze `<INCLUDE_TYPOSCRIPT: …>`-Zeile. */
    @JvmField val INCLUDE = TsTokenType("INCLUDE_TYPOSCRIPT")

    @JvmField val COMMENTS = TokenSet.create(COMMENT, BLOCK_COMMENT)
    @JvmField val VALUES = TokenSet.create(VALUE, OBJECT_TYPE, CONSTANT)
    @JvmField val PATH_PARTS = TokenSet.create(IDENTIFIER, DOT)
    @JvmField val STRINGS = TokenSet.create(IMPORT_PATH)
}

object TsElements {
    @JvmField val OBJECT_PATH = TsElementType("OBJECT_PATH")
    @JvmField val VALUE = TsElementType("VALUE")
    @JvmField val ASSIGNMENT = TsElementType("ASSIGNMENT")
    @JvmField val MULTILINE_ASSIGNMENT = TsElementType("MULTILINE_ASSIGNMENT")
    @JvmField val COPY = TsElementType("COPY")
    @JvmField val REFERENCE = TsElementType("REFERENCE")
    @JvmField val UNSET = TsElementType("UNSET")
    @JvmField val MODIFICATION = TsElementType("MODIFICATION")
    @JvmField val BLOCK = TsElementType("BLOCK")
    /** Pfad ohne Operator - ungültig, aber für die Vervollständigung beim Tippen nötig. */
    @JvmField val INCOMPLETE = TsElementType("INCOMPLETE")
    @JvmField val CONDITION = TsElementType("CONDITION")
    @JvmField val IMPORT = TsElementType("IMPORT")
    @JvmField val INCLUDE = TsElementType("INCLUDE")

    @JvmField val PATH_STATEMENTS = TokenSet.create(ASSIGNMENT, MULTILINE_ASSIGNMENT, COPY, REFERENCE, UNSET, MODIFICATION, BLOCK, INCOMPLETE)
}
