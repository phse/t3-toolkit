package app.heckit.ide.t3toolkit

import app.heckit.ide.t3toolkit.create.NewComponentAction
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

    fun testComponentTagCompletion() {
        setUpExtension()
        myFixture.addFileToProject("packages/my_ext/Resources/Private/Components/Atom/Icon/Icon.html", "<fc:component><fc:renderer/></fc:component>")
        myFixture.addFileToProject("packages/my_ext/Resources/Private/Components/Molecule/Teaser/Components/Inner/Inner.html", "<fc:component/>")
        myFixture.configureByText("Use.html", "{namespace my=Vendor\\MyExt\\Components}\n<my:<caret>")
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertEquals(listOf("my:atom.icon", "my:molecule.teaser"), items.sorted())
        myFixture.lookup.currentItem = myFixture.lookupElements!!.first { it.lookupString == "my:molecule.teaser" }
        myFixture.finishLookup(com.intellij.codeInsight.lookup.Lookup.NORMAL_SELECT_CHAR)
        myFixture.checkResult("{namespace my=Vendor\\MyExt\\Components}\n<my:molecule.teaser title=\"<caret>\"")
    }

    fun testComponentPrefixCompletion() {
        setUpExtension()
        myFixture.configureByText("Prefix.html", "{namespace myComponents=Vendor\\MyExt\\Components}\n<myCo<caret>")
        val items = myFixture.completeBasic()?.map { it.lookupString }
        // Einziger Vorschlag wird direkt eingefügt
        if (items == null) assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.endsWith("<myComponents:"))
        else assertTrue(items.toString(), "myComponents:" in items)
    }

    fun testFluidHrefNotReportedAsFile() {
        val file = myFixture.configureByText("Link.html", "<a href=\"{f:uri.typolink(parameter: teaser.link)}\">x</a>\n<a href=\"missing.html\">y</a>")
        val s = app.heckit.ide.t3toolkit.fluid.FluidHtmlSuppressor()
        val id = app.heckit.ide.t3toolkit.fluid.FluidHtmlSuppressor.TOOL_ID
        assertTrue(s.isSuppressedFor(file.findElementAt(file.text.indexOf("uri.typolink"))!!, id))
        assertFalse(s.isSuppressedFor(file.findElementAt(file.text.indexOf("missing.html"))!!, id))
        assertFalse(s.isSuppressedFor(file.findElementAt(file.text.indexOf("uri.typolink"))!!, "HtmlUnknownAttribute"))
        // Die echte Inspection der IDE meldet den Fluid-Ausdruck nicht mehr, den fehlenden Pfad schon
        myFixture.enableInspections(com.intellij.codeInsight.daemon.impl.analysis.HtmlUnknownTargetInspection())
        val problems = myFixture.doHighlighting().mapNotNull { it.description }.filter { "resolve" in it }
        assertEquals(problems.toString(), 1, problems.size)
        assertTrue(problems.single(), "missing.html" in problems.single())
    }

    fun testMissingReferences() {
        setUpExtension()
        myFixture.addFileToProject("packages/my_ext/Classes/ViewHelpers/IconViewHelper.php", "<?php\nnamespace Vendor\\MyExt\\ViewHelpers;\nclass IconViewHelper {}")
        val tpl = myFixture.addFileToProject("packages/my_ext/Resources/Private/Templates/Missing.html", """
            {namespace my=Vendor\MyExt\Components}
            {namespace x=Vendor\MyExt\ViewHelpers}
            <f:layout name="Nope" />
            <f:render partial="Some/Partial" />
            <f:render partial="Some/Nope" />
            <my:molecule.teaser title="a" />
            <my:molecule.nope />
            <x:icon /><x:whatever />
        """.trimIndent())
        myFixture.configureFromExistingVirtualFile(tpl.virtualFile)
        myFixture.enableInspections(app.heckit.ide.t3toolkit.fluid.FluidMissingReferenceInspection())
        val errors = myFixture.doHighlighting().mapNotNull { it.description }.filter { "not found" in it }.sorted()
        assertEquals(listOf("Component 'my:molecule.nope' not found", "Layout 'Nope' not found", "Partial 'Some/Nope' not found"), errors)
    }

    fun testComponentRelatedAndNewComponent() {
        setUpExtension()
        myFixture.addFileToProject("packages/my_ext/Resources/Private/Components/Molecule/Teaser/Teaser.fixture.json", "{}")
        myFixture.addFileToProject("packages/my_ext/Resources/Private/Components/Molecule/Teaser/Styles/base/_init.less", "")
        val teaser = myFixture.findFileInTempDir("packages/my_ext/Resources/Private/Components/Molecule/Teaser/Teaser.html")
        myFixture.addFileToProject("packages/my_ext/Resources/Private/Components/Molecule/Teaser/Scripts/teaser.js", "")
        assertEquals(listOf("Teaser.fixture.json", "teaser.js", "_init.less"), Fluid.componentRelated(teaser).map { it.name })
        val items = app.heckit.ide.t3toolkit.fluid.ComponentRelatedProvider().getItems(psiManager.findFile(teaser)!!)
        assertEquals(3, items.size)
        val js = myFixture.findFileInTempDir("packages/my_ext/Resources/Private/Components/Molecule/Teaser/Scripts/teaser.js")
        assertEquals(teaser, Fluid.componentOwning(js))
        assertNotNull(Fluid.componentsRootOf(project, teaser.parent))

        val g = app.heckit.ide.t3toolkit.create.NewComponentAction.generate(
            app.heckit.ide.t3toolkit.create.NewComponentAction.Form().apply {
                name = "Card"
                params.clear()
                params += listOf(NewComponentAction.Param("title", "string", false), NewComponentAction.Param("items", "array", true), NewComponentAction.Param("image", "Image", true))
            })
        assertEquals("""<fc:component>
  <fc:param name="title" type="string" />
  <fc:param name="items" type="array" optional="1" />
  <fc:param name="image" type="Image" optional="1" />

  <fc:renderer>
    <div class="card">
      {title}
    </div>
  </fc:renderer>
</fc:component>
""", g.html)
        assertEquals("{\n  \"default\": {\n    \"title\": \"Title\",\n    \"items\": [],\n    \"image\": null\n  }\n}\n", g.fixture)
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
