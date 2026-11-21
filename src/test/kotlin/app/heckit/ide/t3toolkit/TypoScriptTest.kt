package app.heckit.ide.t3toolkit

import app.heckit.ide.t3toolkit.typoscript.TsBlock
import app.heckit.ide.t3toolkit.typoscript.TsCondition
import app.heckit.ide.t3toolkit.typoscript.TsConstantInspection
import app.heckit.ide.t3toolkit.typoscript.TsElements
import app.heckit.ide.t3toolkit.typoscript.TsFile
import app.heckit.ide.t3toolkit.typoscript.TsGotoHandler
import app.heckit.ide.t3toolkit.typoscript.TsImportInspection
import app.heckit.ide.t3toolkit.typoscript.TsIncludeInspection
import app.heckit.ide.t3toolkit.typoscript.TsKind
import app.heckit.ide.t3toolkit.typoscript.TsLexer
import app.heckit.ide.t3toolkit.typoscript.TsRef
import app.heckit.ide.t3toolkit.typoscript.TsStatement
import app.heckit.ide.t3toolkit.typoscript.TsTokens
import app.heckit.ide.t3toolkit.typoscript.TsTypes
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TypoScriptTest : BasePlatformTestCase() {
    private fun tokens(text: String): List<Pair<String, String>> {
        val lexer = TsLexer()
        lexer.start(text)
        val result = ArrayList<Pair<String, String>>()
        while (lexer.tokenType != null) {
            if (lexer.tokenType != TokenType.WHITE_SPACE) result += lexer.tokenType.toString().removePrefix("Ts:") to lexer.tokenText
            lexer.advance()
        }
        return result
    }

    fun testLexerLines() {
        assertEquals(
            listOf("IDENTIFIER" to "page", "DOT" to ".", "IDENTIFIER" to "10", "=" to "=", "OBJECT_TYPE" to "FLUIDTEMPLATE"),
            tokens("page.10 = FLUIDTEMPLATE"),
        )
        // Konstante im Wert, escaped Punkt im Pfad
        assertEquals(
            listOf("IDENTIFIER" to "a\\.b", "DOT" to ".", "IDENTIFIER" to "c", "=" to "=", "VALUE" to "x ", "CONSTANT" to "{\$foo.bar}", "VALUE" to " y"),
            tokens("a\\.b.c = x {\$foo.bar} y"),
        )
        // Nach { ist der Rest der Zeile Kommentar, ebenso nach >
        assertEquals(listOf("IDENTIFIER" to "lib", "{" to "{", "COMMENT" to "foo = bar"), tokens("lib { foo = bar"))
        assertEquals(listOf("IDENTIFIER" to "lib", ">" to ">", "COMMENT" to "# weg"), tokens("lib > # weg"))
        // Kopie mit relativer Quelle
        assertEquals(listOf("IDENTIFIER" to "x", "<" to "<", "DOT" to ".", "IDENTIFIER" to "y"), tokens("x < .y"))
        // Funktion
        assertEquals(listOf("IDENTIFIER" to "l", ":=" to ":=", "FUNCTION_NAME" to "addToList", "FUNCTION_(" to "(", "VALUE" to "a,b", "FUNCTION_)" to ")"),
            tokens("l := addToList(a,b)"))
        // Bedingungen
        assertEquals(listOf("[" to "[", "CONDITION_BODY" to "page[\"uid\"] == 1", "]" to "]"), tokens("[page[\"uid\"] == 1]"))
        assertEquals(listOf("[" to "[", "CONDITION_KEYWORD" to "end", "]" to "]"), tokens("[end]"))
        // Import und Include
        assertEquals(listOf("@import" to "@import", "IMPORT_PATH" to "'EXT:a/b.typoscript'"), tokens("@import 'EXT:a/b.typoscript'"))
        assertEquals("INCLUDE_TYPOSCRIPT", tokens("<INCLUDE_TYPOSCRIPT: source=\"FILE:EXT:a/b.typoscript\">").single().first)
    }

    fun testLexerMultiline() {
        val t = tokens("lib.text (\n  line 1\n  {\$c}\n)\nnext = 1\n/* a\nb */\nz = 1")
        assertEquals(listOf("IDENTIFIER", "DOT", "IDENTIFIER", "(", "VALUE", "CONSTANT", ")", "IDENTIFIER", "=", "VALUE",
            "BLOCK_COMMENT", "IDENTIFIER", "=", "VALUE"), t.map { it.first })
        assertEquals("/* a\nb */", t.first { it.first == "BLOCK_COMMENT" }.second)
    }

    fun testLexerRestartsAtLineStarts() {
        // Ab jedem Token mit Zustand 0 neu begonnen ergibt sich dieselbe Zerlegung
        val text = "page {\n  10 = TEXT\n  10.value (\n    a\n  )\n}\n[END]\n/* x\n*/ \n"
        val full = TsLexer().also { it.start(text) }
        val all = ArrayList<Triple<Int, Int, String>>()
        while (full.tokenType != null) { all += Triple(full.tokenStart, full.state, full.tokenType.toString()); full.advance() }
        for ((start, state, _) in all.filter { it.second == 0 }) {
            val l = TsLexer().also { it.start(text, start, text.length, state) }
            val part = ArrayList<String>()
            while (l.tokenType != null) { part += "${l.tokenStart}:${l.tokenType}"; l.advance() }
            assertEquals(all.filter { it.first >= start }.map { "${it.first}:${it.third}" }, part)
        }
    }

    fun testParser() {
        val file = myFixture.configureByText("setup.typoscript", """
            page = PAGE
            page {
              10 = FLUIDTEMPLATE
              10 {
                templateName = Default
              }
            }
            lib.a < page.10
            [page["uid"] == 1]
            lib.b = 1
            [END]
        """.trimIndent()) as TsFile
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java))
        val paths = PsiTreeUtil.findChildrenOfType(file, TsStatement::class.java).map { it.fullPath() }
        assertEquals(listOf("page", "page", "page.10", "page.10", "page.10.templateName", "lib.a", "lib.b"), paths)
        assertEquals(2, file.statements.count { it is TsCondition })
        assertEquals(TsElements.COPY, PsiTreeUtil.findChildrenOfType(file, TsStatement::class.java).first { it.fullPath() == "lib.a" }.kind)
    }

    fun testUnclosedBlockAndStrayBrace() {
        myFixture.configureByText("setup.typoscript", "page {\n  10 = TEXT\n\n[END]\n}\n")
        val errors = myFixture.doHighlighting().filter { it.severity.name == "ERROR" }.map { it.description }
        assertTrue(errors.toString(), errors.any { it.contains("not closed before the condition") })
        assertTrue(errors.toString(), errors.any { it.contains("Unexpected '}'") })
    }

    fun testTypesAndCompletion() {
        myFixture.addFileToProject("ext/lib.typoscript", "lib.menu = HMENU\nlib.content = FLUIDTEMPLATE\nlib.content.templateName = X\n")
        val file = myFixture.configureByText("setup.typoscript", """
            page = PAGE
            page.10 < lib.content
            page.10.<caret>
        """.trimIndent())
        assertEquals("FLUIDTEMPLATE", TsTypes.typeOf(project, listOf("lib", "content"), TsKind.SETUP))
        assertEquals("FLUIDTEMPLATE", TsTypes.typeOf(project, listOf("page", "10"), TsKind.SETUP))
        assertEquals("CONFIG", TsTypes.typeOf(project, listOf("page", "config"), TsKind.SETUP))
        assertEquals("stdWrap", TsTypes.typeOf(project, listOf("lib", "content", "stdWrap"), TsKind.SETUP))
        val items = myFixture.completeBasic().map { it.lookupString }
        assertTrue(items.toString(), "templateName" in items && "dataProcessing" in items && "variables" in items)
        assertNotNull(file)
    }

    fun testCompletionInBlockAndRoot() {
        myFixture.configureByText("setup.typoscript", "page = PAGE\npage {\n  inc<caret>\n}\n")
        val items = myFixture.completeBasic().map { it.lookupString }
        assertTrue(items.toString(), "includeCSS" in items && "includeJSFooter" in items)

        myFixture.configureByText("other.typoscript", "pa<caret>")
        val roots = myFixture.completeBasic()?.map { it.lookupString }
        // Einziger Treffer wird direkt eingefügt
        if (roots == null) assertEquals("page", myFixture.editor.document.text) else assertTrue(roots.toString(), "page" in roots)
    }

    fun testValueCompletion() {
        myFixture.configureByText("setup.typoscript", "page = PAGE\npage.10 = <caret>")
        val items = myFixture.completeBasic().map { it.lookupString }
        assertTrue(items.toString() + TsTypes.typeOf(project, listOf("page"), TsKind.SETUP) + app.heckit.ide.t3toolkit.typoscript.TsIndex.definitions(project, TsKind.SETUP, "page") + myFixture.file.text, "TEXT" in items && "FLUIDTEMPLATE" in items && "COA" in items)

        myFixture.configureByText("dp.typoscript", "page = PAGE\npage.10 = FLUIDTEMPLATE\npage.10.dataProcessing.10 = m<caret>")
        val dp = myFixture.completeBasic()?.map { it.lookupString }
        if (dp != null) assertTrue("dp " + dp.toString(), "menu" in dp) else assertTrue(myFixture.editor.document.text.endsWith("= menu"))

        myFixture.configureByText("dp2.typoscript", "page = PAGE\npage.10 = FLUIDTEMPLATE\npage.10.dataProcessing.10 = menu\npage.10.dataProcessing.10.lev<caret>")
        val menu = myFixture.completeBasic()?.map { it.lookupString }
        if (menu != null) assertTrue("menu " + menu.toString() + TsTypes.typeOf(project, listOf("page", "10", "dataProcessing", "10"), TsKind.SETUP), "levels" in menu) else assertTrue(myFixture.editor.document.text.endsWith("levels"))
    }

    fun testConstants() {
        myFixture.addFileToProject("ext/constants.typoscript", "site.title = My Site\n")
        myFixture.addFileToProject("ext/Configuration/Sets/Main/settings.definitions.yaml", """
            settings:
              main.color:
                label: Main color
                type: color
                default: '#ff0000'
        """.trimIndent())
        val file = myFixture.configureByText("setup.typoscript", "lib.a = TEXT\nlib.a.value = {\$site.title} {\$main.color} {\$missing.one}\n")
        myFixture.enableInspections(TsConstantInspection())
        val weak = myFixture.doHighlighting().filter { it.description?.contains("is not defined") == true }.map { it.text }
        assertEquals(listOf("{\$missing.one}"), weak)

        val offset = file.text.indexOf("site.title")
        val targets = TsGotoHandler().getGotoDeclarationTargets(file.findElementAt(offset), offset, myFixture.editor)!!
        assertTrue(targets.single().navigationElement.containingFile.name == "constants.typoscript")
        val o2 = file.text.indexOf("main.color")
        val t2 = TsGotoHandler().getGotoDeclarationTargets(file.findElementAt(o2), o2, myFixture.editor)!!
        assertEquals("settings.definitions.yaml", t2.first().containingFile.name)

        myFixture.configureByText("setup2.typoscript", "lib.b = {\$si<caret>")
        val items = myFixture.completeBasic()?.map { it.lookupString }
        if (items != null) assertTrue(items.toString(), "site.title" in items) else assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("{\$site.title}"))
    }

    fun testGotoCopySource() {
        myFixture.addFileToProject("ext/lib.typoscript", "lib.content = FLUIDTEMPLATE\n")
        val file = myFixture.configureByText("setup.typoscript", "page.10 < lib.content\n")
        val offset = file.text.indexOf("content")
        val targets = TsGotoHandler().getGotoDeclarationTargets(file.findElementAt(offset), offset, myFixture.editor)!!
        assertEquals("lib.typoscript", targets.single().navigationElement.containingFile.name)
    }

    fun testImportsAndInclude() {
        myFixture.addFileToProject("packages/my_ext/composer.json", """{"type":"typo3-cms-extension","extra":{"typo3/cms":{"extension-key":"my_ext"}}}""")
        myFixture.addFileToProject("packages/my_ext/Configuration/TypoScript/Helper/a.typoscript", "lib.a = TEXT\n")
        myFixture.configureByText("setup.typoscript", """
            @import 'EXT:my_ext/Configuration/TypoScript/Helper/*.typoscript'
            @import 'EXT:my_ext/Configuration/TypoScript/Missing.typoscript'
            <INCLUDE_TYPOSCRIPT: source="DIR:EXT:my_ext/Configuration/TypoScript/Helper" extensions="typoscript">
        """.trimIndent())
        myFixture.enableInspections(TsImportInspection(), TsIncludeInspection())
        val problems = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue(problems.toString(), problems.any { it.contains("not found") && it.contains("Missing.typoscript") })
        assertFalse(problems.toString(), problems.any { it.contains("not found") && it.contains("Helper") })
        assertTrue(problems.toString(), problems.any { it.contains("deprecated since TYPO3 v13") })
        val fix = myFixture.getAllQuickFixes().first { it.familyName == "Replace with @import" }
        myFixture.launchAction(fix)
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("@import 'EXT:my_ext/Configuration/TypoScript/Helper/*.typoscript'\n@import 'EXT:my_ext/Configuration/TypoScript/Missing.typoscript'\n@import 'EXT:my_ext/Configuration/TypoScript/Helper/*.typoscript'"))
    }

    fun testFormatter() {
        myFixture.configureByText("setup.typoscript", "page   =PAGE\npage {\n10=TEXT\n    10 {\nvalue = a  {\$x}\n}\n  bodyTag>\n}\n")
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(myFixture.file)
        }
        assertEquals("page = PAGE\npage {\n  10 = TEXT\n  10 {\n    value = a  {\$x}\n  }\n  bodyTag >\n}\n", myFixture.editor.document.text)
    }

    fun testFoldingAndStructure() {
        val file = myFixture.configureByText("setup.typoscript", "page {\n  10 = TEXT\n}\n[x]\nlib.a = 1\nlib.b = 1\n[END]\n") as TsFile
        val regions = app.heckit.ide.t3toolkit.typoscript.TsFoldingBuilder().buildFoldRegions(file, myFixture.editor.document, false)
            .map { it.placeholderText }
        assertTrue(regions.toString(), "{...}" in regions && " ..." in regions)
        assertTrue(file.statements.first() is TsBlock)
    }

    fun testRefData() {
        assertNotNull(TsRef.prop("stdWrap", "wrap"))
        assertEquals("stdWrap", TsRef.prop("TEXT", "wrap")?.owner)
        assertEquals("cObject", TsRef.prop("COA", "20")?.childType)
        assertEquals("DP:menu.special", TsRef.prop("DP:menu", "special")?.childType)
        assertNotNull(TsRef.prop("DP:menu.special", "value"))
        assertEquals("PLUGIN_CONFIG", TsRef.prop(TsRef.rootType(false), "plugin")?.let { TsRef.prop(it.childType, "tx_news")?.childType })
        assertEquals("TCEFORM", TsRef.prop(TsRef.rootType(true), "TCEFORM")?.childType)
        assertEquals(TsTokens.COMMENT, TsTokens.COMMENTS.types.first())
    }

    fun testPhpTargetsAndDocs() {
        myFixture.addFileToProject("Classes/UserFunc/Teaser.php", "<?php\nnamespace Vendor\\Ext\\UserFunc;\nclass Teaser { public function render() {} }")
        val file = myFixture.configureByText("setup.typoscript", "lib.t = USER\nlib.t.userFunc = Vendor\\Ext\\UserFunc\\Teaser->render\npage = PAGE\npage.10 = TEXT\npage.10.wrap = <p>|</p>\n")
        val o = file.text.indexOf("Teaser->") + 2
        val cls = TsGotoHandler().getGotoDeclarationTargets(file.findElementAt(o), o, myFixture.editor)!!.single()
        assertEquals("Teaser", (cls as com.jetbrains.php.lang.psi.elements.PhpClass).name)
        val m = file.text.indexOf("render") + 2
        val method = TsGotoHandler().getGotoDeclarationTargets(file.findElementAt(m), m, myFixture.editor)!!.single()
        assertEquals("render", (method as com.jetbrains.php.lang.psi.elements.Method).name)

        val provider = app.heckit.ide.t3toolkit.typoscript.TsDocumentationProvider()
        val w = file.text.indexOf("wrap") + 1
        val doc = provider.generateDoc(provider.getCustomDocumentationElement(myFixture.editor, file, file.findElementAt(w), w), null)!!
        assertTrue(doc, "Wraps the content" in doc && "page.10.wrap" in doc)
        val t = file.text.indexOf("TEXT") + 1
        val typeDoc = provider.generateDoc(provider.getCustomDocumentationElement(myFixture.editor, file, file.findElementAt(t), t), null)!!
        assertTrue(typeDoc, "value" in typeDoc && "stdWrap" in typeDoc)
    }

    fun testTsConfig() {
        myFixture.configureByText("page.tsconfig", "TCEFORM.tt_content.header.<caret>")
        val items = myFixture.completeBasic().map { it.lookupString }
        assertTrue(items.toString(), "disabled" in items && "removeItems" in items)
    }
}
