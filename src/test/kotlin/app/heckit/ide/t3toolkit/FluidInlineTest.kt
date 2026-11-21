package app.heckit.ide.t3toolkit

import app.heckit.ide.t3toolkit.fluid.Extbase
import app.heckit.ide.t3toolkit.fluid.FluidInline
import app.heckit.ide.t3toolkit.fluid.FluidUnknownViewHelperInspection
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.PhpIndex

class FluidInlineTest : BasePlatformTestCase() {
    private val tpl = "packages/my_ext/Resources/Private/Templates/"

    private fun setUpExtension() {
        myFixture.addFileToProject("packages/my_ext/composer.json", """{"type":"typo3-cms-extension","extra":{"typo3/cms":{"extension-key":"my_ext"}}}""")
        myFixture.addFileToProject("vendor/fluid/AbstractViewHelper.php", """<?php
            namespace TYPO3Fluid\Fluid\Core\ViewHelper;
            abstract class AbstractViewHelper { public function initializeArguments() {} protected function registerArgument(${'$'}n, ${'$'}t, ${'$'}d, ${'$'}r = false, ${'$'}def = null) {} }
        """.trimIndent())
        myFixture.addFileToProject("vendor/fluid/Format/DateViewHelper.php", """<?php
            namespace TYPO3Fluid\Fluid\ViewHelpers\Format;
            class DateViewHelper extends \TYPO3Fluid\Fluid\Core\ViewHelper\AbstractViewHelper {
                public function initializeArguments() {
                    ${'$'}this->registerArgument('date', 'mixed', 'The date', false);
                    ${'$'}this->registerArgument('format', 'string', 'Format', false);
                }
            }
            class HtmlViewHelper extends \TYPO3Fluid\Fluid\Core\ViewHelper\AbstractViewHelper {}
        """.trimIndent())
        myFixture.addFileToProject("vendor/cms-fluid/TranslateViewHelper.php", """<?php
            namespace TYPO3\CMS\Fluid\ViewHelpers;
            class TranslateViewHelper extends \TYPO3Fluid\Fluid\Core\ViewHelper\AbstractViewHelper {
                public function initializeArguments() { ${'$'}this->registerArgument('key', 'string', 'Key', true); }
            }
        """.trimIndent())
    }

    private fun complete(path: String, text: String): List<String> {
        val f = myFixture.addFileToProject(path, text.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(f.virtualFile)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("<caret>"))
        return myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
    }

    fun testScanner() {
        val text = "<p>{item.title -> f:format.case(mode: 'upper')}</p> {{ vue }} \${js} {not fluid"
        val exprs = FluidInline.expressions(text)
        assertEquals(1, exprs.size)
        val parts = FluidInline.parts(text, exprs[0]).map { it.kind to text.substring(it.start, it.end) }
        assertEquals(listOf(
            FluidInline.Kind.BRACE to "{", FluidInline.Kind.VARIABLE to "item", FluidInline.Kind.PROPERTY to "title",
            FluidInline.Kind.OPERATOR to "->", FluidInline.Kind.VIEWHELPER to "f:format.case", FluidInline.Kind.ARGUMENT to "mode",
            FluidInline.Kind.STRING to "'upper'", FluidInline.Kind.BRACE to "}",
        ), parts)
        // Verschachtelt und in Skripten nur eindeutige Ausdrücke
        assertEquals(1, FluidInline.expressions("{f:render(arguments: {a: b})}").size)
        assertEquals(0, FluidInline.expressions("function x() { return {a: 1}; }", strict = true).size)
        assertEquals(1, FluidInline.expressions("var x = '{settings.url}';", strict = true).size)
        assertEquals(11, FluidInline.openAt("ab {f:x(a: {b", 0, 13))
        assertNull(FluidInline.openAt("{a} b", 0, 5))
    }

    fun testHighlighting() {
        setUpExtension()
        val f = myFixture.addFileToProject(tpl + "A.html", "<p>{item.title}</p>")
        myFixture.configureFromExistingVirtualFile(f.virtualFile)
        val infos = myFixture.doHighlighting().filter { it.forcedTextAttributesKey?.externalName?.startsWith("T3FLUID") == true }
            .map { it.text to it.forcedTextAttributesKey!!.externalName }
        assertTrue(infos.toString(), ("item" to "T3FLUID_VARIABLE") in infos && ("title" to "T3FLUID_PROPERTY") in infos && ("{" to "T3FLUID_BRACES") in infos)
    }

    fun testInlineCompletion() {
        setUpExtension()
        val vh = complete(tpl + "B.html", "<p>{f:format.<caret></p>")
        assertEquals(listOf("f:format.date", "f:format.html"), vh.sorted())
        val args = complete(tpl + "C.html", "<p>{f:format.date(date: x, <caret>)}</p>")
        assertTrue(args.toString(), args.isEmpty() && myFixture.editor.document.text.contains("date: x, format: )") || args == listOf("format"))
        val vars = complete(tpl + "D.html", "<f:for each=\"{items}\" as=\"teaser\">{tea<caret>}</f:for>")
        assertTrue(vars.toString(), vars.isEmpty() && myFixture.editor.document.text.contains("{teaser}") || "teaser" in vars)
        val chain = complete(tpl + "E.html", "<p>{x -> f:format.d<caret>}</p>")
        assertTrue(chain.toString(), chain.isEmpty() && myFixture.editor.document.text.contains("f:format.date(") || "f:format.date" in chain)
    }

    fun testUnknownViewHelper() {
        setUpExtension()
        val f = myFixture.addFileToProject(tpl + "F.html", "<f:format.dat date=\"x\" />\n{f:translate(key: 'a')} {f:transalte(key: 'a')}\n<f:format.html>x</f:format.html>")
        myFixture.configureFromExistingVirtualFile(f.virtualFile)
        myFixture.enableInspections(FluidUnknownViewHelperInspection())
        val problems = myFixture.doHighlighting().mapNotNull { it.description }.filter { "Unknown ViewHelper" in it }
        assertEquals(listOf("Unknown ViewHelper 'f:format.dat'", "Unknown ViewHelper 'f:transalte'"), problems.sorted())
    }

    fun testIntentions() {
        setUpExtension()
        val f = myFixture.addFileToProject(tpl + "G.html", "<p>{f:translate(key: 'a.b', default: name, arguments: 1.5)}</p>")
        myFixture.configureFromExistingVirtualFile(f.virtualFile)
        myFixture.editor.caretModel.moveToOffset(f.text.indexOf("translate"))
        myFixture.launchAction(myFixture.findSingleIntention("Convert Fluid inline syntax to tag"))
        assertEquals("<p><f:translate key=\"a.b\" default=\"{name}\" arguments=\"1.5\" /></p>", myFixture.editor.document.text)
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("translate"))
        myFixture.launchAction(myFixture.findSingleIntention("Convert Fluid tag to inline syntax"))
        assertEquals("<p>{f:translate(key: 'a.b', default: name, arguments: 1.5)}</p>", myFixture.editor.document.text)

        val g = myFixture.addFileToProject(tpl + "H.html", "<f:format.html>{text}</f:format.html>\n<!-- note -->")
        myFixture.configureFromExistingVirtualFile(g.virtualFile)
        myFixture.editor.caretModel.moveToOffset(3)
        myFixture.launchAction(myFixture.findSingleIntention("Convert Fluid tag to inline syntax"))
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.startsWith("{text -> f:format.html()}"))
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("note"))
        myFixture.launchAction(myFixture.findSingleIntention("Convert to Fluid comment (not rendered)"))
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.endsWith("<f:comment> note </f:comment>"))
    }

    fun testExtbaseActionAndTemplate() {
        setUpExtension()
        myFixture.addFileToProject("packages/my_ext/Classes/Controller/NewsController.php", """<?php
            namespace Vendor\MyExt\Controller;
            class NewsController {
                public function listAction() {
                    ${'$'}this->view->assign('news', []);
                    ${'$'}this->view->assignMultiple(['category' => 1, 'pager' => null]);
                }
            }
        """.trimIndent())
        val list = myFixture.addFileToProject(tpl + "News/List.html", "<p>{<caret>}</p>".replace("<caret>", ""))
        val cls = PhpIndex.getInstance(project).getClassesByFQN("\\Vendor\\MyExt\\Controller\\NewsController").single()
        val action = cls.findOwnMethodByName("listAction")!!
        assertEquals(listOf(list.virtualFile), Extbase.templatesFor(project, action))
        assertEquals(listOf(action), Extbase.actionsFor(project, list.virtualFile, "News/List"))
        assertEquals(listOf("news", "category", "pager"), Extbase.assignedVariables(action))
        val vars = complete(tpl + "News/Show.html", "<p>{n<caret>}</p>")
        // Show hat keine Action - nur Datei-Variablen und settings
        assertFalse(vars.toString(), "news" in vars)
        val listVars = complete(tpl + "News/List2.html", "x")
        assertNotNull(listVars)
    }

    fun testInlineArgumentDoc() {
        setUpExtension()
        val f = myFixture.addFileToProject(tpl + "I.html", "<p>{f:format.date(date: x, format: 'Y')}</p>")
        myFixture.configureFromExistingVirtualFile(f.virtualFile)
        val provider = T3DocumentationProvider()
        val o = f.text.indexOf("format:") + 1
        val doc = provider.generateDoc(provider.getCustomDocumentationElement(myFixture.editor, f, f.findElementAt(o), o), null)!!
        assertTrue(doc, "Format" in doc && "f:format.date" in doc)
    }

    fun testBraceHighlighting() {
        setUpExtension()
        val f = myFixture.addFileToProject(tpl + "J.html", "<p>{f:render(arguments: {a: b})}</p>")
        myFixture.configureFromExistingVirtualFile(f.virtualFile)
        val factory = app.heckit.ide.t3toolkit.fluid.FluidBraceHighlighterFactory()
        fun pair(at: Int): List<Int> {
            myFixture.editor.caretModel.moveToOffset(at)
            @Suppress("UNCHECKED_CAST")
            val h = factory.createHighlightUsagesHandler(myFixture.editor, f, f.findElementAt(at)!!)
                as com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase<com.intellij.psi.PsiElement>
            h.computeUsages(h.targets)
            return h.readUsages.map { it.startOffset }.sorted()
        }
        val text = f.text
        assertEquals(listOf(text.indexOf("{f"), text.lastIndexOf("}")), pair(text.indexOf("{f")))
        assertEquals(listOf(text.indexOf("{a"), text.indexOf("})")), pair(text.indexOf("{a")))
        assertEquals(listOf(text.indexOf("("), text.indexOf(")")), pair(text.indexOf(")")))
    }

    fun testUnboundFluidPrefixNotReported() {
        setUpExtension()
        val f = myFixture.addFileToProject(tpl + "K.html", "{namespace my=Vendor\\Ext\\ViewHelpers}\n<f:format.html>x</f:format.html>\n<my:thing />\n<other:tag />")
        myFixture.configureFromExistingVirtualFile(f.virtualFile)
        myFixture.enableInspections(com.intellij.codeInsight.daemon.impl.analysis.XmlUnboundNsPrefixInspection())
        val unbound = myFixture.doHighlighting().filter { it.description?.contains("is not bound") == true }.map { it.text }
        assertEquals(listOf("other"), unbound)
    }
}
