package app.heckit.ide.t3toolkit

import app.heckit.ide.t3toolkit.fluid.ViewHelperArgumentInspection
import app.heckit.ide.t3toolkit.icons.IconGotoHandler
import app.heckit.ide.t3toolkit.xliff.RenameLabelHandler
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FluidExtrasTest : BasePlatformTestCase() {
    private fun setUpExtension() {
        myFixture.addFileToProject("packages/my_ext/composer.json", """{"type":"typo3-cms-extension","extra":{"typo3/cms":{"extension-key":"my_ext"}}}""")
        myFixture.addFileToProject("vendor/fluid/AbstractViewHelper.php", """<?php
            namespace TYPO3Fluid\Fluid\Core\ViewHelper;
            abstract class AbstractViewHelper { public function initializeArguments() {} protected function registerArgument(${'$'}n, ${'$'}t, ${'$'}d, ${'$'}r = false, ${'$'}def = null) {} }
            abstract class AbstractTagBasedViewHelper extends AbstractViewHelper {}
        """.trimIndent())
        myFixture.addFileToProject("vendor/fluid/Format/HtmlViewHelper.php", """<?php
            namespace TYPO3Fluid\Fluid\ViewHelpers\Format;
            /**
             * Renders HTML safely.
             *
             * More text.
             */
            class HtmlViewHelper extends \TYPO3Fluid\Fluid\Core\ViewHelper\AbstractViewHelper {
                public function initializeArguments() {
                    ${'$'}this->registerArgument('value', 'string', 'The HTML to render', true);
                    ${'$'}this->registerArgument('parseFuncTSPath', 'string', 'TypoScript path', false, 'lib.parseFunc_RTE');
                }
            }
            class CurrencyViewHelper extends \TYPO3Fluid\Fluid\Core\ViewHelper\AbstractViewHelper {
                public function initializeArguments() { ${'$'}this->registerArgument('currencySign', 'string', 'Sign', true); }
            }
        """.trimIndent())
        myFixture.addFileToProject("packages/my_ext/Resources/Private/Partials/Teaser/List.html", "<f:section name=\"Item\">x</f:section>\n<f:section name=\"Empty\">y</f:section>")
        myFixture.addFileToProject("packages/my_ext/Resources/Private/Partials/Footer.html", "f")
        myFixture.addFileToProject("packages/my_ext/Resources/Private/Layouts/Default.html", "l")
        myFixture.addFileToProject("packages/my_ext/Configuration/Icons.php", """<?php
            return [
                'my-logo' => ['provider' => SvgIconProvider::class, 'source' => 'EXT:my_ext/Resources/Public/Icons/logo.svg'],
                'my-star' => ['provider' => SvgIconProvider::class, 'source' => 'EXT:my_ext/Resources/Public/Icons/star.svg'],
            ];
        """.trimIndent())
    }

    private fun complete(path: String, text: String): List<String> {
        val f = myFixture.addFileToProject(path, text.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(f.virtualFile)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("<caret>"))
        return myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
    }

    fun testNameCompletion() {
        setUpExtension()
        val tpl = "packages/my_ext/Resources/Private/Templates/"
        assertEquals(listOf("Footer", "Teaser/List"), complete(tpl + "A.html", "<f:render partial=\"<caret>\" />").sorted())
        assertEquals(listOf("Empty", "Item"), complete(tpl + "B.html", "<f:render partial=\"Teaser/List\" section=\"<caret>\" />").sorted())
        assertEquals(listOf("Own"), complete(tpl + "C.html", "<f:section name=\"Own\"/>\n<f:render section=\"<caret>\" />"))
        val layout = complete(tpl + "D.html", "<f:layout name=\"De<caret>\" />")
        assertTrue(layout.toString(), layout.isEmpty() && myFixture.editor.document.text.contains("\"Default\""))
    }

    fun testViewHelperCompletionAndInspection() {
        setUpExtension()
        val tpl = "packages/my_ext/Resources/Private/Templates/"
        val tags = complete(tpl + "E.html", "<f:format.<caret>")
        assertEquals(listOf("f:format.currency", "f:format.html"), tags.sorted())
        val loose = complete(tpl + "E2.html", "<fformath<caret>")
        assertTrue(loose.toString(), loose.isEmpty() && myFixture.editor.document.text.startsWith("<f:format.html"))
        val loose2 = complete(tpl + "E3.html", "<curr<caret>")
        assertTrue(loose2.toString(), "f:format.currency" in loose2 || myFixture.editor.document.text.startsWith("<f:format.currency"))
        val args = complete(tpl + "F.html", "<f:format.html <caret> />")
        assertTrue(args.toString(), "value" in args && "parseFuncTSPath" in args)

        val f = myFixture.addFileToProject(tpl + "G.html", "<f:format.currency />\n<f:format.html>{x}</f:format.html>")
        myFixture.configureFromExistingVirtualFile(f.virtualFile)
        myFixture.enableInspections(ViewHelperArgumentInspection())
        assertEquals(listOf("Missing required argument: currencySign"),
            myFixture.doHighlighting().mapNotNull { it.description }.filter { "required argument" in it })
    }

    fun testViewHelperDoc() {
        setUpExtension()
        val f = myFixture.addFileToProject("packages/my_ext/Resources/Private/Templates/H.html", "<f:format.html value=\"x\" />")
        myFixture.configureFromExistingVirtualFile(f.virtualFile)
        val provider = T3DocumentationProvider()
        val offset = f.text.indexOf("html")
        val el = provider.getCustomDocumentationElement(myFixture.editor, f, f.findElementAt(offset), offset)!!
        val doc = provider.generateDoc(el, null)!!
        assertTrue(doc, "Renders HTML safely." in doc && "The HTML to render" in doc && "lib.parseFunc_RTE" in doc)
        val aOff = f.text.indexOf("value")
        val arg = provider.generateDoc(provider.getCustomDocumentationElement(myFixture.editor, f, f.findElementAt(aOff), aOff), null)!!
        assertTrue(arg, "The HTML to render" in arg && "yes" in arg)
    }

    fun testIcons() {
        setUpExtension()
        val tpl = "packages/my_ext/Resources/Private/Templates/"
        val icons = complete(tpl + "I.html", "<core:icon identifier=\"my-<caret>\" />")
        assertEquals(listOf("my-logo", "my-star"), icons.sorted())
        val f = myFixture.addFileToProject(tpl + "J.html", "<core:icon identifier=\"my-star\" size=\"small\" />")
        myFixture.configureFromExistingVirtualFile(f.virtualFile)
        val offset = f.text.indexOf("my-star") + 2
        val t = IconGotoHandler().getGotoDeclarationTargets(f.findElementAt(offset), offset, myFixture.editor)!!.single()
        assertEquals("Icons.php", t.navigationElement.containingFile.name)
        assertTrue(t.navigationElement.text.contains("my-star"))
    }

    fun testLabelDocAndRename() {
        setUpExtension()
        myFixture.addFileToProject("packages/my_ext/Resources/Private/Language/locallang.xlf",
            "<xliff><file><body>\n  <trans-unit id=\"old.key\" resname=\"old.key\"><source>Hello</source></trans-unit>\n</body></file></xliff>")
        myFixture.addFileToProject("packages/my_ext/Resources/Private/Language/de.locallang.xlf",
            "<xliff><file><body>\n  <trans-unit id=\"old.key\"><target>Hallo</target></trans-unit>\n</body></file></xliff>")
        val php = myFixture.addFileToProject("packages/my_ext/Configuration/TCA/t.php",
            "<?php\n\$a = 'LLL:EXT:my_ext/Resources/Private/Language/locallang.xlf:old.key';")
        myFixture.addFileToProject("packages/my_ext/Resources/Private/Templates/K.html", "<f:translate key=\"old.key\" />")
        myFixture.configureFromExistingVirtualFile(php.virtualFile)
        val provider = T3DocumentationProvider()
        val offset = php.text.indexOf("old.key")
        val doc = provider.generateDoc(provider.getCustomDocumentationElement(myFixture.editor, php, php.findElementAt(offset), offset), null)!!
        assertTrue(doc, "Hello" in doc && "Hallo" in doc && ">de<" in doc)

        val ll = myFixture.findFileInTempDir("packages/my_ext/Resources/Private/Language/locallang.xlf")
        RenameLabelHandler.rename(project, ll, "old.key", "new.key")
        fun text(p: String) = FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(p))!!.text
        assertTrue(text("packages/my_ext/Resources/Private/Language/locallang.xlf").contains("id=\"new.key\" resname=\"new.key\""))
        assertTrue(text("packages/my_ext/Resources/Private/Language/de.locallang.xlf").contains("id=\"new.key\""))
        assertTrue(text("packages/my_ext/Configuration/TCA/t.php").contains("locallang.xlf:new.key'"))
        assertTrue(text("packages/my_ext/Resources/Private/Templates/K.html").contains("key=\"new.key\""))
    }
}
