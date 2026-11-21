package app.heckit.ide.t3toolkit.typoscript

import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.application.options.IndentOptionsEditor
import com.intellij.application.options.SmartIndentOptionsEditor
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.formatting.Alignment
import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.SpacingBuilder
import com.intellij.formatting.Wrap
import com.intellij.lang.ASTNode
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import com.intellij.psi.formatter.common.AbstractBlock
import com.intellij.psi.tree.TokenSet

/** Einrückung je Blockebene, ein Leerzeichen um Operatoren und vor `{` bzw. `(`; Werte und Bedingungen bleiben unangetastet. */
class TsFormattingModelBuilder : FormattingModelBuilder {
    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val settings = formattingContext.codeStyleSettings
        val spacing = SpacingBuilder(settings, TsLanguage)
            .before(TokenSet.create(TsTokens.OP_ASSIGN, TsTokens.OP_COPY, TsTokens.OP_REFERENCE, TsTokens.OP_MODIFY, TsTokens.OP_UNSET)).spaces(1)
            .after(TokenSet.create(TsTokens.OP_ASSIGN, TsTokens.OP_COPY, TsTokens.OP_REFERENCE, TsTokens.OP_MODIFY)).spaces(1)
            .before(TsTokens.LBRACE).spaces(1)
            .before(TsTokens.MULTILINE_START).spaces(1)
        val root = TsFormatBlock(formattingContext.node, Indent.getNoneIndent(), spacing)
        return FormattingModelProvider.createFormattingModelForPsiFile(formattingContext.containingFile, root, settings)
    }
}

private class TsFormatBlock(node: ASTNode, private val indent: Indent, private val spacing: SpacingBuilder) :
    AbstractBlock(node, null as Wrap?, null as Alignment?) {

    override fun buildChildren(): List<Block> {
        if (isLeaf) return emptyList()
        val result = ArrayList<Block>()
        var insideBraces = false
        var child = myNode.firstChildNode
        while (child != null) {
            val type = child.elementType
            if (type == TsTokens.LBRACE) insideBraces = true
            if (type != TokenType.WHITE_SPACE && child.textLength > 0) {
                val childIndent = if (myNode.elementType == TsElements.BLOCK && insideBraces && type != TsTokens.LBRACE && type != TsTokens.RBRACE)
                    Indent.getNormalIndent() else Indent.getNoneIndent()
                result += TsFormatBlock(child, childIndent, spacing)
            }
            if (type == TsTokens.RBRACE) insideBraces = false
            child = child.treeNext
        }
        return result
    }

    override fun getIndent(): Indent = indent
    override fun getSpacing(child1: Block?, child2: Block): Spacing? = spacing.getSpacing(this, child1, child2)

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes =
        if (myNode.elementType == TsElements.BLOCK) ChildAttributes(Indent.getNormalIndent(), null)
        else ChildAttributes(Indent.getNoneIndent(), null)

    override fun isIncomplete(): Boolean =
        myNode.elementType == TsElements.BLOCK && myNode.findChildByType(TsTokens.RBRACE) == null || super.isIncomplete()

    /** Pfade, Werte, Bedingungen, Includes und Tokens nicht zerlegen. */
    override fun isLeaf(): Boolean = myNode.firstChildNode == null || myNode.elementType in LEAVES

    private companion object {
        val LEAVES = TokenSet.create(TsElements.OBJECT_PATH, TsElements.VALUE, TsElements.CONDITION, TsElements.IMPORT, TsElements.INCLUDE)
    }
}

class TsCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {
    override fun getLanguage() = TsLanguage
    override fun getConfigurableDisplayName() = "TypoScript"

    override fun getCodeSample(settingsType: SettingsType) = """
        page = PAGE
        page {
          10 = FLUIDTEMPLATE
          10 {
            templateName = Default
            variables {
              title = TEXT
              title.field = title
            }
          }
        }
        lib.footer < lib.content
    """.trimIndent()

    override fun getIndentOptionsEditor(): IndentOptionsEditor = SmartIndentOptionsEditor()

    override fun customizeDefaults(commonSettings: CommonCodeStyleSettings, indentOptions: CommonCodeStyleSettings.IndentOptions) {
        // Wie die .editorconfig des TYPO3-Core: 2 Leerzeichen
        indentOptions.INDENT_SIZE = 2
        indentOptions.CONTINUATION_INDENT_SIZE = 4
        indentOptions.TAB_SIZE = 2
    }

    override fun createConfigurable(baseSettings: CodeStyleSettings, modelSettings: CodeStyleSettings): CodeStyleConfigurable =
        object : CodeStyleAbstractConfigurable(baseSettings, modelSettings, "TypoScript") {
            override fun createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel =
                object : TabbedLanguageCodeStylePanel(TsLanguage, currentSettings, settings) {
                    override fun initTabs(settings: CodeStyleSettings) { addIndentOptionsTab(settings) }
                }
        }
}
