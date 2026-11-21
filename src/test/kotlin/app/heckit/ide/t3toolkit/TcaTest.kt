package app.heckit.ide.t3toolkit

import app.heckit.ide.t3toolkit.tca.Tca
import app.heckit.ide.t3toolkit.tca.TcaGotoHandler
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TcaTest : BasePlatformTestCase() {
    private val tcaDir = "packages/my_ext/Configuration/TCA/"

    private fun setUpTables() {
        myFixture.addFileToProject(tcaDir + "tx_myext_item.php", """<?php
            return [
                'ctrl' => ['title' => 'Item', 'label' => 'title'],
                'columns' => [
                    'title' => ['label' => 'Title', 'config' => ['type' => 'input']],
                    'parent' => ['label' => 'Parent', 'config' => ['type' => 'passthrough']],
                ],
                'palettes' => ['dates' => ['showitem' => 'title']],
            ];
        """.trimIndent())
        myFixture.addFileToProject(tcaDir + "Overrides/tx_myext_item.php", """<?php
            ${'$'}tempColumns = ['extra' => ['label' => 'Extra', 'config' => ['type' => 'check']]];
            \TYPO3\CMS\Core\Utility\ExtensionManagementUtility::addTCAcolumns('tx_myext_item', ${'$'}tempColumns);
        """.trimIndent())
        myFixture.addFileToProject("packages/my_ext/ext_tables.sql", "CREATE TABLE tx_myext_item_mm (uid_local int);\n")
    }

    private fun complete(path: String, text: String): List<String> {
        val f = myFixture.addFileToProject(path, text.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(f.virtualFile)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("<caret>"))
        return myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
    }

    fun testTablesAndColumns() {
        setUpTables()
        assertTrue("tx_myext_item" in Tca.tables(project))
        assertEquals(setOf("title", "parent", "extra"), Tca.columns(project, "tx_myext_item").keys)
        assertEquals(setOf("dates"), Tca.palettes(project, "tx_myext_item").keys)
        assertTrue("tx_myext_item_mm" in Tca.sqlTables(project))
    }

    fun testKeyAndValueCompletion() {
        setUpTables()
        val types = complete(tcaDir + "tx_a.php", "<?php\nreturn ['columns' => ['x' => ['config' => ['type' => '<caret>']]]];")
        assertTrue(types.toString(), "input" in types && "select" in types && "datetime" in types)
        val keys = complete(tcaDir + "tx_b.php", "<?php\nreturn ['columns' => ['x' => ['config' => ['type' => 'select', '<caret>']]]];")
        assertTrue(keys.toString(), "renderType" in keys && "foreign_table" in keys && "items" in keys && "type" !in keys)
        val render = complete(tcaDir + "tx_c.php", "<?php\nreturn ['columns' => ['x' => ['config' => ['type' => 'select', 'renderType' => '<caret>']]]];")
        assertTrue(render.toString(), "selectSingle" in render && "selectTree" in render)
        val tables = complete(tcaDir + "tx_d.php", "<?php\nreturn ['columns' => ['x' => ['config' => ['type' => 'select', 'foreign_table' => 'tx_my<caret>']]]];")
        assertTrue(tables.toString(), tables.isEmpty() && myFixture.editor.document.text.contains("'tx_myext_item'") || "tx_myext_item" in tables)
        val ctrl = complete(tcaDir + "tx_myext_item2.php", "<?php\nreturn ['ctrl' => ['<caret>']];")
        assertTrue(ctrl.toString(), "label" in ctrl && "languageField" in ctrl)
        val root = complete(tcaDir + "tx_e.php", "<?php\nreturn ['<caret>'];")
        assertEquals(listOf("columns", "ctrl", "palettes", "types"), root.sorted())
        val override = complete(tcaDir + "Overrides/tt_x.php", "<?php\n\$GLOBALS['TCA']['tx_myext_item']['ctrl']['label'] = 'x';\n\$GLOBALS['TCA']['tx_myext_item']['columns']['y'] = ['config' => ['type' => 'check', 'renderType' => '<caret>']];")
        assertTrue(override.toString(), "checkboxToggle" in override)
    }

    fun testShowitemCompletionAndGoto() {
        setUpTables()
        val items = complete(tcaDir + "tx_myext_item3.php", "<?php\nreturn ['types' => ['0' => ['showitem' => 'a, <caret>']]];")
        assertNotNull(items)
        val f = myFixture.addFileToProject(tcaDir + "Overrides/tx_myext_item_types.php", """<?php
            ${'$'}GLOBALS['TCA']['tx_myext_item']['types']['0']['showitem'] = 'title, --palette--;;dates, extra';
            ${'$'}GLOBALS['TCA']['tx_myext_item']['columns']['rel'] = ['config' => ['type' => 'inline', 'foreign_table' => 'tx_myext_item', 'foreign_field' => 'parent', 'MM' => 'tx_myext_item_mm']];
        """.trimIndent())
        myFixture.configureFromExistingVirtualFile(f.virtualFile)
        fun target(marker: String, shift: Int = 1): String? {
            val o = f.text.indexOf(marker) + shift
            return TcaGotoHandler().getGotoDeclarationTargets(f.findElementAt(o), o, myFixture.editor)?.single()?.let {
                it.navigationElement.containingFile.name + ":" + it.navigationElement.text.take(20)
            }
        }
        assertEquals("tx_myext_item.php:'title'", target("title,"))
        assertEquals("tx_myext_item.php:'dates'", target("dates"))
        assertEquals("tx_myext_item.php:'extra'", target("extra'"))
        assertTrue(target("tx_myext_item', 'foreign_field") ?.startsWith("tx_myext_item.php") == true)
        assertEquals("tx_myext_item.php:'parent'", target("parent'"))
        assertTrue(target("tx_myext_item_mm")?.startsWith("ext_tables.sql") == true)
    }
}
