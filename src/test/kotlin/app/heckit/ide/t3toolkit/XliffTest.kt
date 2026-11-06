package app.heckit.ide.t3toolkit

import app.heckit.ide.t3toolkit.settings.T3Settings
import app.heckit.ide.t3toolkit.xliff.Xliff
import app.heckit.ide.t3toolkit.xliff.XliffGotoHandler
import app.heckit.ide.t3toolkit.xliff.XliffMissingInspection
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class XliffTest : BasePlatformTestCase() {
    private fun setUpExtension() {
        myFixture.addFileToProject("packages/my_ext/composer.json", """{"type":"typo3-cms-extension","extra":{"typo3/cms":{"extension-key":"my_ext"}}}""")
        myFixture.addFileToProject("packages/my_ext/Resources/Private/Language/locallang.xlf", """
            <?xml version="1.0" encoding="UTF-8"?>
            <xliff version="1.2"><file source-language="en" datatype="plaintext" original="messages"><body>
              <trans-unit id="teaser.title"><source>Teaser title</source></trans-unit>
              <trans-unit id="more"><source><![CDATA[Read more]]></source></trans-unit>
            </body></file></xliff>
        """.trimIndent())
        myFixture.addFileToProject("packages/my_ext/Resources/Private/Language/de.locallang.xlf", """
            <xliff version="1.2"><file target-language="de"><body>
              <trans-unit id="more"><source>Read more</source><target>Mehr</target></trans-unit>
            </body></file></xliff>
        """.trimIndent())
        myFixture.addFileToProject("packages/my_ext/Resources/Private/Language/locallang_db.xlf", """
            <xliff version="1.2"><file><body>
              <trans-unit id="tx_myext_item"><source>Item</source></trans-unit>
            </body></file></xliff>
        """.trimIndent())
    }

    private fun target(text: String, marker: String, name: String = "packages/my_ext/Configuration/TCA/x.php"): com.intellij.psi.PsiElement? {
        val file = myFixture.addFileToProject(name, text)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val offset = text.indexOf(marker) + 1
        return XliffGotoHandler().getGotoDeclarationTargets(file.findElementAt(offset), offset, myFixture.editor)?.single()
    }

    fun testGotoKeyAndFile() {
        setUpExtension()
        val php = "<?php\nreturn ['label' => 'LLL:EXT:my_ext/Resources/Private/Language/locallang_db.xlf:tx_myext_item'];"
        val tag = target(php, "tx_myext_item") as XmlTag
        assertEquals("trans-unit", tag.name)
        assertTrue(tag.text.contains("tx_myext_item"))
        val file = target(php.replace("x.php", "y.php"), "Resources/Private", "packages/my_ext/Configuration/TCA/y.php")
        assertEquals("locallang_db.xlf", (file as com.intellij.psi.PsiFile).name)
    }

    fun testLocalizationUtility() {
        setUpExtension()
        val php = "<?php\n\$a = LocalizationUtility::translate('teaser.title', 'my_ext');\n\$b = LocalizationUtility::translate('more', 'MyExt');\n\$c = LocalizationUtility::translate('x.' . \$y, 'my_ext');"
        assertTrue(target(php, "teaser.title")!!.text.contains("teaser.title"))
        assertTrue(target(php.replace("x.php", ""), "more'", "packages/my_ext/Classes/B.php")!!.text.contains("Read more"))
        assertNull(target(php, "x.'", "packages/my_ext/Classes/C.php"))
    }

    fun testCustomCalls() {
        setUpExtension()
        val settings = T3Settings.get(project)
        settings.update(true, "", "my:label(name, ext)\nLabels::get(_, key, extension)\n->t(key)\nlbl(key)")
        try {
            val php = "<?php\n\$a = Labels::get(\$x, 'teaser.title', 'my_ext');\n\$b = \$this->t('more');\n\$c = lbl(key: 'more');\n\$d = Labels::get(\$x, foo('a', 'b'), 'my_ext');"
            assertTrue(target(php, "teaser.title")!!.text.contains("teaser.title"))
            assertTrue(target(php, "more')", "packages/my_ext/Classes/B.php")!!.text.contains("Read more"))
            assertTrue(target(php, "more');\n\$d", "packages/my_ext/Classes/C.php")!!.text.contains("Read more"))
            assertNull(target(php, "a', 'b'", "packages/my_ext/Classes/D.php"))
            val html = "<my:label name=\"more\" ext=\"MyExt\" />"
            assertTrue(target(html, "more", "packages/my_ext/Resources/Private/Templates/X.html")!!.text.contains("Read more"))
        } finally {
            settings.update(true, "", "")
        }
    }

    fun testShortKeyInFluid() {
        setUpExtension()
        val html = "<p><f:translate key=\"teaser.title\" /></p>\n{f:translate(key: 'more', extensionName: 'MyExt')}"
        assertTrue(target(html, "teaser.title", "packages/my_ext/Resources/Private/Templates/A.html")!!.text.contains("teaser.title"))
        assertTrue(target(html, "more'", "packages/my_ext/Resources/Private/Templates/B.html")!!.text.contains("Read more"))
        // Eigener Translate-ViewHelper, inline mit verschachtelten Argumenten
        val own = "<f:variable name=\"x\" value=\"{my:translate(key: 'teaser.title', arguments: {0: price})}\" />"
        assertTrue(target(own, "teaser.title", "packages/my_ext/Resources/Private/Templates/C.html")!!.text.contains("teaser.title"))
        // Schlüssel aus einer anderen Extension (ohne extensionName)
        myFixture.addFileToProject("packages/other/composer.json", """{"type":"typo3-cms-extension","extra":{"typo3/cms":{"extension-key":"other"}}}""")
        val other = "<my:translate key=\"teaser.title\" />"
        assertTrue(target(other, "teaser.title", "packages/other/Resources/Private/Templates/D.html")!!.text.contains("teaser.title"))
    }

    fun testMissingInspection() {
        setUpExtension()
        val file = myFixture.addFileToProject("packages/my_ext/Configuration/TCA/z.php", """<?php
            return [
              'a' => 'LLL:EXT:my_ext/Resources/Private/Language/locallang_db.xlf:tx_myext_item',
              'b' => 'LLL:EXT:my_ext/Resources/Private/Language/locallang_db.xlf:nope',
              'c' => 'LLL:EXT:my_ext/Resources/Private/Language/missing.xlf:x',
            ];
        """.trimIndent())
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.enableInspections(XliffMissingInspection())
        val messages = myFixture.doHighlighting().mapNotNull { it.description }.filter { "not found" in it }.sorted()
        assertEquals(listOf(
            "Label 'nope' not found in EXT:my_ext/Resources/Private/Language/locallang_db.xlf",
            "Language file 'EXT:my_ext/Resources/Private/Language/missing.xlf' not found",
        ), messages)
    }

    fun testCompletion() {
        setUpExtension()
        myFixture.configureByText("t.php", "<?php\n\$x = 'LLL:EXT:my_ext/Resources/Private/Language/locallang.xlf:tea<caret>';")
        val items = myFixture.completeBasic()
        if (items == null) assertTrue(myFixture.editor.document.text.contains("locallang.xlf:teaser.title'"))
        else assertTrue(items.map { it.lookupString }.toString(), items.any { it.lookupString == "teaser.title" })

        myFixture.configureByText("u.php", "<?php\n\$x = 'LLL:EXT:my_<caret>';")
        val files = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue(files.toString(), "EXT:my_ext/Resources/Private/Language/locallang_db.xlf:" in files)
        assertFalse(files.toString(), files.any { "de.locallang" in it })
    }

    fun testUsages() {
        setUpExtension()
        myFixture.addFileToProject("packages/my_ext/Configuration/TCA/item.php",
            "<?php\nreturn ['title' => 'LLL:EXT:my_ext/Resources/Private/Language/locallang_db.xlf:tx_myext_item'];")
        myFixture.addFileToProject("packages/my_ext/Resources/Private/Templates/C.html", "<f:translate key=\"more\" />")
        val db = myFixture.findFileInTempDir("packages/my_ext/Resources/Private/Language/locallang_db.xlf")
        assertEquals(1, Xliff.usages(project, db)["tx_myext_item"]?.size)
        val ll = myFixture.findFileInTempDir("packages/my_ext/Resources/Private/Language/locallang.xlf")
        assertEquals(1, Xliff.usages(project, ll)["more"]?.size)
        val de = myFixture.findFileInTempDir("packages/my_ext/Resources/Private/Language/de.locallang.xlf")
        assertEquals(ll, Xliff.defaultOf(de))
    }
}

class XliffExtrasTest : BasePlatformTestCase() {
    private fun setUp2() {
        myFixture.addFileToProject("packages/my_ext/composer.json", """{"type":"typo3-cms-extension","extra":{"typo3/cms":{"extension-key":"my_ext"}}}""")
        myFixture.addFileToProject("packages/my_ext/Resources/Private/Language/locallang.xlf",
            "<xliff><file><body>\n    <trans-unit id=\"a\"><source>Alpha text</source></trans-unit>\n    <trans-unit id=\"b\"><source>Beta</source></trans-unit>\n  </body></file></xliff>")
        myFixture.addFileToProject("packages/my_ext/Resources/Private/Language/de.locallang.xlf",
            "<xliff><file><body>\n    <trans-unit id=\"a\"><target>A</target></trans-unit>\n    <trans-unit id=\"old\"><target>X</target></trans-unit>\n  </body></file></xliff>")
    }

    fun testFolding() {
        setUp2()
        val f = myFixture.addFileToProject("packages/my_ext/x.php", "<?php\n\$a = 'LLL:EXT:my_ext/Resources/Private/Language/locallang.xlf:a';")
        myFixture.configureFromExistingVirtualFile(f.virtualFile)
        val regions = app.heckit.ide.t3toolkit.xliff.XliffFolding().buildFoldRegions(f, myFixture.editor.document, false)
        assertEquals(listOf("„Alpha text“"), regions.map { it.placeholderText })
        assertEquals("LLL:EXT:my_ext/Resources/Private/Language/locallang.xlf:a", regions.single().range.substring(f.text))
    }

    fun testCreateLabelFix() {
        setUp2()
        val f = myFixture.addFileToProject("packages/my_ext/y.php", "<?php\n\$a = 'LLL:EXT:my_ext/Resources/Private/Language/locallang.xlf:new.key';")
        myFixture.configureFromExistingVirtualFile(f.virtualFile)
        myFixture.enableInspections(XliffMissingInspection())
        myFixture.editor.caretModel.moveToOffset(f.text.indexOf("new.key") + 2)
        myFixture.doHighlighting()
        myFixture.launchAction(myFixture.findSingleIntention("Create label 'new.key' in locallang.xlf"))
        val xlf = myFixture.findFileInTempDir("packages/my_ext/Resources/Private/Language/locallang.xlf")
        val text = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(xlf)!!.text
        assertTrue(text, text.contains("    <trans-unit id=\"new.key\" resname=\"new.key\">\n      <source>new.key</source>\n    </trans-unit>\n  </body>"))
    }

    fun testTranslationInspection() {
        setUp2()
        myFixture.enableInspections(app.heckit.ide.t3toolkit.xliff.XliffTranslationInspection())
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("packages/my_ext/Resources/Private/Language/locallang.xlf"))
        assertEquals(listOf("Label 'b' is not translated: de"), myFixture.doHighlighting().mapNotNull { it.description }.filter { "Label" in it })
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("packages/my_ext/Resources/Private/Language/de.locallang.xlf"))
        assertEquals(listOf("Label 'old' does not exist in locallang.xlf"), myFixture.doHighlighting().mapNotNull { it.description }.filter { "Label" in it })
    }
}
