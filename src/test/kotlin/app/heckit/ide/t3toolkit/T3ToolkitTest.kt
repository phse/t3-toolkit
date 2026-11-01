package app.heckit.ide.t3toolkit

import app.heckit.ide.t3toolkit.fluid.Fluid
import app.heckit.ide.t3toolkit.fluid.FluidGotoHandler
import app.heckit.ide.t3toolkit.services.ServicesGotoHandler
import app.heckit.ide.t3toolkit.services.ServicesIndex
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class T3ToolkitTest : BasePlatformTestCase() {
    private fun setUpExtension() {
        myFixture.addFileToProject("packages/my_ext/composer.json", """{"type":"typo3-cms-extension","extra":{"typo3/cms":{"extension-key":"my_ext"}}}""")
        myFixture.addFileToProject("packages/my_ext/ext_localconf.php", """<?php
            ${'$'}GLOBALS['TYPO3_CONF_VARS']['EXTCONF']['fluid_components']['namespaces']['Vendor\\MyExt\\Components'] =
                \TYPO3\CMS\Core\Utility\ExtensionManagementUtility::extPath('my_ext', 'Resources/Private/Components');
        """.trimIndent())
        myFixture.addFileToProject("packages/my_ext/Resources/Private/Partials/Some/Partial.html", "<p>partial</p>\n<f:section name=\"Inner\">x</f:section>")
        myFixture.addFileToProject("packages/my_ext/Resources/Private/Layouts/Default.html", "<div><f:render section=\"Main\"/></div>")
        myFixture.addFileToProject("packages/my_ext/Resources/Private/Components/Molecule/Teaser/Teaser.html", """
            <fc:component>
              <fc:param name="title" type="string" />
              <fc:param name="items" type="array" optional="1" />
              <fc:renderer>{title}</fc:renderer>
            </fc:component>
        """.trimIndent())
    }

    private fun gotoTargets(file: PsiFile, marker: String, handler: com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler): List<String> {
        val offset = file.text.indexOf(marker) + 1
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val targets = handler.getGotoDeclarationTargets(file.findElementAt(offset), offset, myFixture.editor)
            ?: (file.findReferenceAt(offset) as? com.intellij.psi.PsiPolyVariantReference)?.multiResolve(false)?.mapNotNull { it.element }?.toTypedArray()
            ?: file.findReferenceAt(offset)?.resolve()?.let { arrayOf(it) }
            ?: emptyArray()
        return targets.map { (it.containingFile?.virtualFile ?: (it as? com.intellij.psi.PsiDirectory)?.virtualFile)!!.path + if (it is PsiFile) "" else "#" + it.text.take(30) }
    }

    fun testPartialLayoutSectionAndComponent() {
        setUpExtension()
        val tpl = myFixture.addFileToProject("packages/my_ext/Resources/Private/Templates/Page.html", """
            {namespace my=Vendor\MyExt\Components}
            <f:layout name="Default" />
            <f:render partial="Some/Partial" section="Inner" />
            {f:render(partial: 'Some/Partial')}
            <my:molecule.teaser title="x" />
            <my:molecule.teaser items="{a}" />
            <img src="EXT:my_ext/Resources/Private/Layouts/Default.html" />
        """.trimIndent())
        val h = FluidGotoHandler()
        assertTrue(gotoTargets(tpl, "Default\"", h).single().endsWith("Layouts/Default.html"))
        assertTrue(gotoTargets(tpl, "Some/Partial\" ", h).single().endsWith("Partials/Some/Partial.html"))
        assertTrue(gotoTargets(tpl, "Some/Partial')", h).single().endsWith("Partials/Some/Partial.html"))
        assertTrue(gotoTargets(tpl, "Inner", h).single().contains("Partial.html#"))
        assertTrue(gotoTargets(tpl, "molecule.teaser title", h).single().endsWith("Teaser/Teaser.html"))
        assertTrue(gotoTargets(tpl, "Resources/Private/Layouts/Default.html\"", h).single().endsWith("Layouts/Default.html"))

        // Rückwärts
        val partial = myFixture.findFileInTempDir("packages/my_ext/Resources/Private/Partials/Some/Partial.html")
        val role = Fluid.roleOf(project, partial, String(partial.contentsToByteArray()))!!
        assertEquals("Some/Partial", role.name)
        assertEquals(2, Fluid.usages(project, partial, role).size)
        val component = myFixture.findFileInTempDir("packages/my_ext/Resources/Private/Components/Molecule/Teaser/Teaser.html")
        val cRole = Fluid.roleOf(project, component, String(component.contentsToByteArray()))!!
        assertEquals("Component", cRole.kind)
        assertEquals(2, Fluid.usages(project, component, cRole).size)

        // Pflichtparameter
        myFixture.configureFromExistingVirtualFile(tpl.virtualFile)
        myFixture.enableInspections(app.heckit.ide.t3toolkit.fluid.ComponentParamInspection())
        val warnings = myFixture.doHighlighting().filter { it.description?.contains("Missing required") == true }
        assertEquals(listOf("Missing required parameter: title"), warnings.map { it.description })
    }

    fun testComponentCompletion() {
        setUpExtension()
        myFixture.configureByText("Use.html", "{namespace my=Vendor\\MyExt\\Components}\n<my:molecule.teaser <caret> />")
        // Datei liegt außerhalb, Namespace ist trotzdem registriert
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue(items.toString(), "title" in items && "items" in items)
    }

    fun testServicesYaml() {
        setUpExtension()
        myFixture.addFileToProject("packages/my_ext/Classes/Listener/Foo.php", "<?php\nnamespace Vendor\\MyExt\\Listener;\nclass Foo { public function onEvent() {} }")
        myFixture.addFileToProject("packages/my_ext/Classes/Domain/Model/Bar.php", "<?php\nnamespace Vendor\\MyExt\\Domain\\Model;\nclass Bar {}")
        myFixture.addFileToProject("packages/my_ext/Classes/Service/Baz.php", "<?php\nnamespace Vendor\\MyExt\\Service;\nclass Baz {}")
        val yaml = myFixture.addFileToProject("packages/my_ext/Configuration/Services.yaml", """
            services:
              _defaults:
                autowire: true
              Vendor\MyExt\:
                resource: '../Classes/*'
                exclude: '../Classes/Domain/Model/*'
              Vendor\MyExt\Listener\Foo:
                public: true
                arguments:
                  ${'$'}baz: '@Vendor\MyExt\Service\Baz'
                tags:
                  - name: event.listener
                    identifier: 'my-listener'
                    method: 'onEvent'
              my.alias:
                alias: Vendor\MyExt\Service\Baz
        """.trimIndent())
        val index = ServicesIndex.get(project)
        fun labels(cls: String, path: String) = index.usagesOf(cls, myFixture.findFileInTempDir(path)).map { it.label }
        val foo = labels("Vendor\\MyExt\\Listener\\Foo", "packages/my_ext/Classes/Listener/Foo.php")
        assertTrue(foo.toString(), foo.any { "public: true" in it && "tag event.listener" in it } && foo.any { "autodiscovered" in it })
        val baz = labels("Vendor\\MyExt\\Service\\Baz", "packages/my_ext/Classes/Service/Baz.php")
        assertTrue(baz.toString(), baz.any { "injected into 'Vendor\\MyExt\\Listener\\Foo'" in it } && baz.any { "alias 'my.alias'" in it })
        assertTrue(labels("Vendor\\MyExt\\Domain\\Model\\Bar", "packages/my_ext/Classes/Domain/Model/Bar.php").isEmpty())

        val h = ServicesGotoHandler()
        val key = gotoTargets(yaml, "MyExt\\Listener\\Foo:", h)
        assertTrue(key.toString(), key.single().contains("Foo.php#class Foo {"))
        assertTrue(gotoTargets(yaml, "MyExt\\Service\\Baz'", h).single().endsWith("Baz.php#class Baz {}"))
        assertTrue(gotoTargets(yaml, "onEvent", h).single().contains("Foo.php#public function onEvent"))
        val dir = gotoTargets(yaml, "./Classes/*'", h); assertTrue(dir.toString(), dir.single().contains("my_ext/Classes"))
    }

    fun testViewHelpers() {
        setUpExtension()
        myFixture.addFileToProject("packages/my_ext/ext_tables.php", "<?php")
        myFixture.addFileToProject("vendor/fluid/HtmlViewHelper.php", """<?php
            namespace TYPO3\CMS\Fluid\ViewHelpers\Format;
            abstract class AbstractVh { public function initializeArguments() { ${'$'}this->registerArgument('parseFuncTSPath', 'string', ''); } }
            class HtmlViewHelper extends AbstractVh {}
        """.trimIndent())
        myFixture.addFileToProject("packages/my_ext/Classes/ViewHelpers/Asset/CssViewHelper.php", """<?php
            namespace Vendor\MyExt\ViewHelpers\Asset;
            class CssViewHelper { public function initializeArguments() { ${'$'}this->registerArgument('href', 'string', ''); } }
        """.trimIndent())
        myFixture.addFileToProject("packages/other/ext_localconf.php", """<?php
            ${'$'}GLOBALS['TYPO3_CONF_VARS']['SYS']['fluid']['namespaces']['my'] = ['Vendor\\MyExt\\ViewHelpers'];
        """.trimIndent())
        val tpl = myFixture.addFileToProject("packages/my_ext/Resources/Private/Templates/Vh.html", """
            <f:format.html parseFuncTSPath="lib.x">a</f:format.html>
            <my:asset.css href="x" />
            {x -> my:asset.css(href: 'y')}
        """.trimIndent())
        val h = FluidGotoHandler()
        assertTrue(gotoTargets(tpl, "format.html parse", h).single().contains("HtmlViewHelper.php#class HtmlViewHelper"))
        assertTrue(gotoTargets(tpl, "parseFuncTSPath=", h).single().contains("#'parseFuncTSPath'"))
        assertTrue(gotoTargets(tpl, "asset.css href=\"x", h).single().contains("CssViewHelper.php#class CssViewHelper"))
        assertTrue(gotoTargets(tpl, "ref=\"x", h).single().contains("#'href'"))
        assertTrue(gotoTargets(tpl, "sset.css(", h).single().contains("CssViewHelper.php#class"))
        assertTrue(gotoTargets(tpl, "ref: 'y'", h).single().contains("#'href'"))
    }

    fun testStructuresAsPartialRootFromTypoScript() {
        setUpExtension()
        myFixture.addFileToProject("packages/my_ext/Configuration/TypoScript/constants.typoscript",
            "styles.templates {\n  structuresRootPath = EXT:my_ext/Resources/Private/Structures/\n}\n")
        myFixture.addFileToProject("packages/my_ext/Configuration/TypoScript/setup.typoscript", """
            plugin.tx_x {
              view {
                partialRootPaths {
                  0 = EXT:my_ext/Resources/Private/Partials
                  2 = {${'$'}styles.templates.structuresRootPath}
                }
              }
            }
        """.trimIndent())
        myFixture.addFileToProject("packages/my_ext/Resources/Private/Structures/Checkout/Nucleus/Contact.html", "{namespace my=X}\n<div/>")
        val user = myFixture.addFileToProject("packages/my_ext/Resources/Private/Structures/Checkout/Organisms/Init.html",
            "<f:render partial=\"Checkout/Nucleus/Contact\" />")
        assertTrue(gotoTargets(user, "Checkout/Nucleus", FluidGotoHandler()).single().endsWith("Structures/Checkout/Nucleus/Contact.html"))
        val contact = myFixture.findFileInTempDir("packages/my_ext/Resources/Private/Structures/Checkout/Nucleus/Contact.html")
        val role = Fluid.roleOf(project, contact, String(contact.contentsToByteArray()))!!
        assertEquals("Partials" to "Checkout/Nucleus/Contact", role.kind to role.name)
        assertEquals(1, Fluid.usages(project, contact, role).size)
    }

    fun testVariables() {
        setUpExtension()
        val h = FluidGotoHandler()
        val comp = myFixture.findFileInTempDir("packages/my_ext/Resources/Private/Components/Molecule/Teaser/Teaser.html")
        val compPsi = psiManager.findFile(comp)!!
        assertTrue(gotoTargets(compPsi, "title}", h).single().contains("name=\"title\""))
        val tpl = myFixture.addFileToProject("packages/my_ext/Resources/Private/Templates/Var.html", """
            <f:for each="{items}" as="item" iteration="it">
              <span>{item.name} {it.index}</span>
              <f:render partial="Some/Entry" arguments="{entry: item}" />
            </f:for>
        """.trimIndent())
        assertTrue(gotoTargets(tpl, "item.name", h).single().contains("Var.html#"))
        assertTrue(gotoTargets(tpl, "it.index", h).single().contains("Var.html#"))
        assertTrue(gotoTargets(tpl, "item}\" />", h).single().contains("Var.html#"))
        // Partial: woher kommt {entry}?
        val partial = myFixture.addFileToProject("packages/my_ext/Resources/Private/Partials/Some/Entry.html", "<p>{entry.title}</p>")
        val from = gotoTargets(partial, "entry.title", h)
        assertTrue(from.toString(), from.single().let { "Var.html#" in it && "entry" in it })
        // Template-Variable aus PHP
        myFixture.addFileToProject("packages/my_ext/Classes/Controller/C.php", "<?php class C { function a() { ${'$'}this->view->assign('items', []); } }")
        val php = gotoTargets(tpl, "items}", h)
        assertTrue(php.toString(), php.any { it.contains("C.php#") })
    }

    fun testMultilineArgumentsAndProcessedData() {
        setUpExtension()
        myFixture.addFileToProject("packages/my_ext/Classes/DataProcessing/P.php",
            "<?php class P { function process() { ${'$'}processedData['teasers'] = []; } }")
        val tpl = myFixture.addFileToProject("packages/my_ext/Resources/Private/Templates/Booking.html", """
            <f:render partial="Some/Partial" arguments="{uid: data.uid,
                                                       teasers: teasers
                                                       }"/>
        """.trimIndent())
        val r = gotoTargets(tpl, "easers\n", FluidGotoHandler())
        assertTrue(r.toString(), r.any { it.contains("P.php#") })
    }
}
