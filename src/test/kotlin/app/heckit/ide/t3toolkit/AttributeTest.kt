package app.heckit.ide.t3toolkit

import app.heckit.ide.t3toolkit.create.NewT3ClassAction
import app.heckit.ide.t3toolkit.services.ServicesAttributeInspection
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AttributeTest : BasePlatformTestCase() {
    private fun stubs(version: String) {
        myFixture.addFileToProject("composer.lock", """{"packages":[{"name": "typo3/cms-core", "version": "v$version"}]}""")
        val v13 = version.startsWith("13")
        val classes = buildList {
            add("Symfony\\Component\\Console\\Attribute\\AsCommand")
            add("Symfony\\Component\\DependencyInjection\\Attribute\\Autoconfigure")
            if (v13) {
                add("TYPO3\\CMS\\Core\\Attribute\\AsEventListener")
                add("Symfony\\Component\\Messenger\\Attribute\\AsMessageHandler")
            }
        }
        classes.forEachIndexed { i, fqn ->
            myFixture.addFileToProject("vendor/stub$i.php", "<?php\nnamespace ${fqn.substringBeforeLast('\\')};\nclass ${fqn.substringAfterLast('\\')} {}")
        }
        myFixture.addFileToProject("packages/my_ext/composer.json",
            """{"type":"typo3-cms-extension","autoload":{"psr-4":{"Vendor\\MyExt\\":"Classes/"}},"extra":{"typo3/cms":{"extension-key":"my_ext"}}}""")
    }

    fun testQuickFixMovesListenerAndCommandToAttributes() {
        stubs("13.4.36")
        myFixture.addFileToProject("packages/my_ext/Classes/Listener/Foo.php", """<?php

namespace Vendor\MyExt\Listener;

use TYPO3\CMS\Core\Mail\Event\AfterMailerSentMessageEvent;

/**
 * Doc
 */
final class Foo
{
}
""")
        myFixture.addFileToProject("packages/my_ext/Classes/Command/Run.php", "<?php\n\nnamespace Vendor\\MyExt\\Command;\n\nclass Run\n{\n}\n")
        val yaml = myFixture.addFileToProject("packages/my_ext/Configuration/Services.yaml", """services:
  _defaults:
    autowire: true
    autoconfigure: true
    public: false

  Vendor\MyExt\:
    resource: '../Classes/*'

  Vendor\MyExt\Listener\Foo:
    tags:
      - name: event.listener
        identifier: 'my-ext/foo'
        event: TYPO3\CMS\Core\Mail\Event\AfterMailerSentMessageEvent

  Vendor\MyExt\Command\Run:
    arguments:
      ${'$'}x: 1
    tags:
      - name: 'console.command'
        command: 'my:run'
        description: 'Runs it'
      - name: 'console.command'
        command: 'my:go'
        alias: true
""")
        myFixture.configureFromExistingVirtualFile(yaml.virtualFile)
        myFixture.enableInspections(ServicesAttributeInspection())
        val fixes = myFixture.getAllQuickFixes().filter { it.text.startsWith("Move to") }
        assertEquals(listOf("Move to #[AsEventListener]", "Move to #[AsCommand]"), fixes.map { it.text })
        fixes.forEach { myFixture.launchAction(it) }
        assertEquals("""services:
  _defaults:
    autowire: true
    autoconfigure: true
    public: false

  Vendor\MyExt\:
    resource: '../Classes/*'

  Vendor\MyExt\Command\Run:
    arguments:
      ${'$'}x: 1
""", yaml.viewProvider.document!!.text)
        val foo = myFixture.findFileInTempDir("packages/my_ext/Classes/Listener/Foo.php")
        val fooText = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(foo)!!.text
        assertEquals("""<?php

namespace Vendor\MyExt\Listener;

use TYPO3\CMS\Core\Mail\Event\AfterMailerSentMessageEvent;
use TYPO3\CMS\Core\Attribute\AsEventListener;

/**
 * Doc
 */
#[AsEventListener(identifier: 'my-ext/foo', event: AfterMailerSentMessageEvent::class)]
final class Foo
{
}
""", fooText)
        val run = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            .getDocument(myFixture.findFileInTempDir("packages/my_ext/Classes/Command/Run.php"))!!.text
        assertTrue(run, "use Symfony\\Component\\Console\\Attribute\\AsCommand;" in run &&
            "#[AsCommand(name: 'my:run', description: 'Runs it', aliases: ['my:go'])]\nclass Run" in run)
    }

    fun testNoSuggestionOnV12ForListener() {
        stubs("12.4.20")
        myFixture.addFileToProject("packages/my_ext/Classes/Listener/Foo.php", "<?php\nnamespace Vendor\\MyExt\\Listener;\nclass Foo {}")
        val yaml = myFixture.addFileToProject("packages/my_ext/Configuration/Services.yaml", """services:
  _defaults:
    autoconfigure: true
  Vendor\MyExt\Listener\Foo:
    tags:
      - name: event.listener
""")
        myFixture.configureFromExistingVirtualFile(yaml.virtualFile)
        myFixture.enableInspections(ServicesAttributeInspection())
        assertTrue(myFixture.getAllQuickFixes().none { it.text.startsWith("Move to") })
    }

    fun testGenerateEventListener() {
        stubs("13.4.36")
        val form = NewT3ClassAction.Form("Vendor\\MyExt\\EventListener", "Foo", "my_ext").apply {
            eventClass = "TYPO3\\CMS\\Core\\Mail\\Event\\AfterMailerSentMessageEvent"; identifier = "my_ext/foo"
        }
        val g = NewT3ClassAction.generate(NewT3ClassAction.Kind.EVENT_LISTENER, form, T3Version.get(project))
        assertNull(g.yaml)
        assertEquals("""<?php

declare(strict_types=1);

namespace Vendor\MyExt\EventListener;

use TYPO3\CMS\Core\Attribute\AsEventListener;
use TYPO3\CMS\Core\Mail\Event\AfterMailerSentMessageEvent;

#[AsEventListener(identifier: 'my_ext/foo')]
final class Foo
{
    public function __invoke(AfterMailerSentMessageEvent ${'$'}event): void
    {
    }
}
""", g.php)
        val v12 = NewT3ClassAction.generate(NewT3ClassAction.Kind.COMMAND, form.apply { commandName = "my:run" }, T3Version.get(project))
        assertTrue(v12.php, "#[AsCommand(name: 'my:run')]" in v12.php && "extends Command" in v12.php)
    }
}
