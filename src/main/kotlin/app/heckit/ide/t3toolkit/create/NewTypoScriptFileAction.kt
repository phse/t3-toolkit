package app.heckit.ide.t3toolkit.create

import app.heckit.ide.t3toolkit.typoscript.TsIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile

/** Neue TypoScript- bzw. TSconfig-Datei (leer, Endung wird ergänzt). */
class NewTypoScriptFileAction : DumbAwareAction("TypoScript File", "New TypoScript (.typoscript) or TSconfig (.tsconfig) file", TsIcons.FILE) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(LangDataKeys.IDE_VIEW)?.directories?.isNotEmpty() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dir = e.getData(LangDataKeys.IDE_VIEW)?.directories?.firstOrNull()?.virtualFile ?: return
        val validator = object : InputValidator {
            override fun checkInput(inputString: String) = fileName(inputString).let { it.length > ".typoscript".length && dir.findChild(it) == null && '/' !in it }
            override fun canClose(inputString: String) = checkInput(inputString)
        }
        val name = Messages.showInputDialog(project, "File name (.typoscript is added unless you type .tsconfig):", "New TypoScript File",
            TsIcons.FILE, "setup", validator) ?: return
        var created: VirtualFile? = null
        WriteCommandAction.runWriteCommandAction(project, "New TypoScript File", null, {
            created = dir.createChildData(this, fileName(name))
        })
        created?.let { OpenFileDescriptor(project, it).navigate(true) }
    }

    private fun fileName(input: String): String {
        val n = input.trim()
        return if (n.endsWith(".typoscript") || n.endsWith(".tsconfig")) n else "$n.typoscript"
    }
}
