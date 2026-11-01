package app.heckit.ide.t3toolkit.create

import app.heckit.ide.t3toolkit.T3Project
import app.heckit.ide.t3toolkit.T3Version
import app.heckit.ide.t3toolkit.php.PhpAttribute
import app.heckit.ide.t3toolkit.php.PhpAttributes
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/** Neue Klasse mit PHP-Attribut statt Services.yaml (bzw. mit YAML-Eintrag, wo die TYPO3-Version das Attribut nicht kennt). */
abstract class NewT3ClassAction(private val kind: Kind) : DumbAwareAction(kind.title, kind.description, com.jetbrains.php.PhpIcons.PHP_FILE) {

    enum class Kind(val title: String, val description: String, val defaultName: String, val dir: String) {
        EVENT_LISTENER("Event Listener", "PSR-14 event listener with #[AsEventListener]", "MyEventListener", "EventListener"),
        COMMAND("Console Command", "Console command with #[AsCommand]", "MyCommand", "Command"),
        MESSAGE_HANDLER("Message Handler", "Message handler with #[AsMessageHandler]", "MyMessageHandler", "MessageHandler"),
        BACKEND_CONTROLLER("Backend Controller", "Backend module controller with #[AsController]", "MyModuleController", "Controller"),
        PUBLIC_SERVICE("Public Service", "Service with #[Autoconfigure(public: true)]", "MyService", "Service"),
    }

    class EventListener : NewT3ClassAction(Kind.EVENT_LISTENER)
    class Command : NewT3ClassAction(Kind.COMMAND)
    class MessageHandler : NewT3ClassAction(Kind.MESSAGE_HANDLER)
    class BackendController : NewT3ClassAction(Kind.BACKEND_CONTROLLER)
    class PublicService : NewT3ClassAction(Kind.PUBLIC_SERVICE)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val dir = e.getData(LangDataKeys.IDE_VIEW)?.directories?.firstOrNull()?.virtualFile
        e.presentation.isEnabledAndVisible = e.project != null && dir != null && Psr4.namespaceFor(dir) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dir = e.getData(LangDataKeys.IDE_VIEW)?.directories?.firstOrNull()?.virtualFile ?: return
        val t3 = T3Project.get(project)
        val extKey = t3.extensionOf(dir)?.first ?: "my_ext"
        val form = Form(Psr4.namespaceFor(dir).orEmpty(), kind.defaultName, extKey)
        if (!FormDialog(project, kind, form).showAndGet()) return
        val v = T3Version.get(project)
        val generated = generate(kind, form, v)
        var created: VirtualFile? = null
        WriteCommandAction.runWriteCommandAction(project, "New ${kind.title}", null, {
            val file = dir.createChildData(this, form.className + ".php")
            VfsUtilCore.saveText(file, generated.php)
            created = file
            generated.yaml?.let { appendToServicesYaml(project, dir, form, it) }
        })
        created?.let { OpenFileDescriptor(project, it).navigate(true) }
    }

    private fun appendToServicesYaml(project: Project, dir: VirtualFile, form: Form, yaml: String) {
        val services = T3Project.get(project).extensionOf(dir)?.second?.findFileByRelativePath("Configuration/Services.yaml")
        val entry = "\n  ${form.fqn}:\n$yaml"
        val note = "TYPO3 ${T3Version.get(project).label} does not support the attribute for this yet"
        if (services == null) {
            notify(project, "$note. Add this to Configuration/Services.yaml:\n$entry", NotificationType.WARNING)
            return
        }
        val text = VfsUtilCore.loadText(services).trimEnd('\n', ' ')
        VfsUtilCore.saveText(services, text + "\n" + entry + "\n")
        notify(project, "$note - registered in ${T3Project.get(project).extPathOf(services) ?: services.path} instead.", NotificationType.INFORMATION)
    }

    private fun notify(project: Project, text: String, type: NotificationType) =
        NotificationGroupManager.getInstance().getNotificationGroup("T3 Toolkit").createNotification(text, type).notify(project)

    class Form(var namespace: String, var className: String, extKey: String) {
        var eventClass = ""
        var identifier = "$extKey/"
        var commandName = "$extKey:"
        var description = ""
        var messageClass = ""
        var public = true
        val fqn get() = (namespace.trim('\\') + "\\" + className).trimStart('\\')
    }

    private class FormDialog(project: Project, private val kind: Kind, private val form: Form) : DialogWrapper(project) {
        init {
            title = "New ${kind.title}"
            init()
        }

        override fun createCenterPanel(): JComponent = panel {
            row("Namespace:") { textField().bindText(form::namespace).columns(COLUMNS_LARGE) }
            row("Class name:") {
                textField().bindText(form::className).columns(COLUMNS_LARGE).focused()
                    .validationOnApply { if (Regex("[A-Za-z_][A-Za-z0-9_]*").matches(it.text.trim())) null else error("Invalid class name") }
            }
            when (kind) {
                Kind.EVENT_LISTENER -> {
                    row("Event class:") { textField().bindText(form::eventClass).columns(COLUMNS_LARGE).comment("Fully qualified, e.g. TYPO3\\CMS\\Core\\Mail\\Event\\AfterMailerSentMessageEvent") }
                    row("Identifier:") { textField().bindText(form::identifier).columns(COLUMNS_LARGE) }
                }
                Kind.COMMAND -> {
                    row("Command:") { textField().bindText(form::commandName).columns(COLUMNS_LARGE) }
                    row("Description:") { textField().bindText(form::description).columns(COLUMNS_LARGE) }
                }
                Kind.MESSAGE_HANDLER -> row("Message class:") { textField().bindText(form::messageClass).columns(COLUMNS_LARGE) }
                Kind.PUBLIC_SERVICE -> row { checkBox("Public (GeneralUtility::makeInstance, \$container->get())").bindSelected(form::public) }
                Kind.BACKEND_CONTROLLER -> row { comment("Register the module in Configuration/Backend/Modules.php.") }
            }
        }
    }

    class Generated(val php: String, val yaml: String?)

    companion object {
        fun generate(kind: Kind, form: Form, v: T3Version): Generated {
            val imports = sortedSetOf<String>()
            val attrs = ArrayList<PhpAttribute>()
            var yaml: String? = null
            var extends = ""
            val body: String
            fun short(fqn: String): String { imports += fqn.trimStart('\\'); return fqn.trimStart('\\').substringAfterLast('\\') }
            when (kind) {
                Kind.EVENT_LISTENER -> {
                    val event = form.eventClass.trim().trimStart('\\')
                    val param = if (event.isEmpty()) "object \$event" else "${short(event)} \$event"
                    if (v.eventListener) attrs += PhpAttribute(T3Version.AS_EVENT_LISTENER, listOf("identifier" to PhpAttributes.str(form.identifier)))
                    else yaml = "    tags:\n      - name: event.listener\n        identifier: '${form.identifier}'\n"
                    body = "    public function __invoke($param): void\n    {\n    }\n"
                }
                Kind.COMMAND -> {
                    val args = mutableListOf("name" to PhpAttributes.str(form.commandName))
                    if (form.description.isNotBlank()) args += "description" to PhpAttributes.str(form.description)
                    if (v.command) attrs += PhpAttribute(T3Version.AS_COMMAND, args)
                    else yaml = "    tags:\n      - name: console.command\n        command: '${form.commandName}'\n" +
                        (if (form.description.isNotBlank()) "        description: '${form.description}'\n" else "")
                    extends = " extends " + short("Symfony\\Component\\Console\\Command\\Command")
                    val input = short("Symfony\\Component\\Console\\Input\\InputInterface")
                    val output = short("Symfony\\Component\\Console\\Output\\OutputInterface")
                    body = "    protected function execute($input \$input, $output \$output): int\n    {\n        return Command::SUCCESS;\n    }\n"
                }
                Kind.MESSAGE_HANDLER -> {
                    val message = form.messageClass.trim().trimStart('\\')
                    val param = if (message.isEmpty()) "object \$message" else "${short(message)} \$message"
                    if (v.messageHandler) attrs += PhpAttribute(T3Version.AS_MESSAGE_HANDLER)
                    else yaml = "    tags:\n      - name: messenger.message_handler\n"
                    body = "    public function __invoke($param): void\n    {\n    }\n"
                }
                Kind.BACKEND_CONTROLLER -> {
                    v.controller?.let { attrs += PhpAttribute(it) } ?: run { yaml = "    tags:\n      - name: backend.controller\n" }
                    val request = short("Psr\\Http\\Message\\ServerRequestInterface")
                    val response = short("Psr\\Http\\Message\\ResponseInterface")
                    val factory = short("TYPO3\\CMS\\Backend\\Template\\ModuleTemplateFactory")
                    body = "    public function __construct(\n        private readonly $factory \$moduleTemplateFactory,\n    ) {}\n\n" +
                        "    public function indexAction($request \$request): $response\n    {\n" +
                        "        \$view = \$this->moduleTemplateFactory->create(\$request);\n" +
                        "        return \$view->renderResponse('${form.className.removeSuffix("Controller")}/Index');\n    }\n"
                }
                Kind.PUBLIC_SERVICE -> {
                    if (form.public) {
                        if (v.autoconfigure) attrs += PhpAttribute(T3Version.AUTOCONFIGURE, listOf("public" to "true"))
                        else yaml = "    public: true\n"
                    }
                    body = ""
                }
            }
            val attrLines = PhpAttributes.renderStandalone(attrs, imports)
            val php = buildString {
                append("<?php\n\ndeclare(strict_types=1);\n\n")
                if (form.namespace.isNotBlank()) append("namespace ${form.namespace.trim('\\')};\n\n")
                if (imports.isNotEmpty()) append(imports.joinToString("\n") { "use $it;" }).append("\n\n")
                attrLines.forEach { append(it).append('\n') }
                append("final class ${form.className}$extends\n{\n").append(body).append("}\n")
            }
            return Generated(php, yaml)
        }
    }
}

/** Namespace eines Ordners aus `autoload.psr-4` der nächsten composer.json. */
object Psr4 {
    private val BLOCK = Regex(""""psr-4"\s*:\s*\{([^}]*)}""")
    private val ENTRY = Regex(""""((?:[^"\\]|\\.)*)"\s*:\s*"([^"]*)"""")

    fun namespaceFor(dir: VirtualFile): String? {
        var current: VirtualFile? = dir
        while (current != null) {
            val composer = current.findChild("composer.json")
            if (composer != null) {
                val text = runCatching { VfsUtilCore.loadText(composer) }.getOrNull() ?: return null
                val root = current
                BLOCK.findAll(text).flatMap { ENTRY.findAll(it.groupValues[1]) }.forEach { m ->
                    val prefix = m.groupValues[1].replace("\\\\", "\\").trimEnd('\\')
                    val base = root.findFileByRelativePath(m.groupValues[2].trim('/').ifEmpty { "." }) ?: return@forEach
                    if (VfsUtilCore.isAncestor(base, dir, false)) {
                        val rel = VfsUtilCore.getRelativePath(dir, base)?.replace('/', '\\').orEmpty()
                        return listOf(prefix, rel).filter { it.isNotEmpty() }.joinToString("\\")
                    }
                }
                return null
            }
            current = current.parent
        }
        return null
    }
}
