package app.heckit.ide.t3toolkit.create

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.TextFieldWithAutoCompletionListProvider
import com.jetbrains.php.PhpIcons
import com.jetbrains.php.PhpIndex

/** Eingabefeld für die Event-Klasse mit Vervollständigung aus allen Klassen `*Event` des Projekts (inkl. vendor). */
object EventClassField {
    fun create(project: Project, initial: String): TextFieldWithAutoCompletion<String> =
        TextFieldWithAutoCompletion(project, Provider(loadWithProgress(project)), true, initial)

    /** In großen Projekten (vendor) dauert das Durchsuchen spürbar - nicht ohne Fortschrittsanzeige im EDT. */
    private fun loadWithProgress(project: Project): List<String> {
        var result = emptyList<String>()
        com.intellij.openapi.progress.ProgressManager.getInstance().runProcessWithProgressSynchronously(
            { result = eventClasses(project) }, "Collecting Event Classes", true, project,
        )
        return result
    }

    fun eventClasses(project: Project): List<String> {
        if (DumbService.isDumb(project)) return emptyList()
        return ApplicationManager.getApplication().runReadAction(Computable {
            val index = PhpIndex.getInstance(project)
            index.getAllClassNames(null).asSequence()
                .filter { it.endsWith("Event") && !it.startsWith("Abstract") }
                .flatMap { index.getClassesByName(it).asSequence() }
                .filter { !it.isAbstract && !it.isInterface && !it.isTrait }
                .map { it.fqn.trimStart('\\') }
                .distinct().sorted().toList()
        })
    }

    private class Provider(items: List<String>) : TextFieldWithAutoCompletionListProvider<String>(items) {
        override fun getLookupString(item: String) = item
        override fun createLookupBuilder(item: String): LookupElementBuilder =
            LookupElementBuilder.create(item, item)
                .withLookupString(item.substringAfterLast('\\'))
                .withPresentableText(item.substringAfterLast('\\'))
                .withTypeText(item.substringBeforeLast('\\', ""), true)
                .withIcon(PhpIcons.CLASS)
        override fun getPrefix(text: String, offset: Int) = text.substring(0, offset).trimStart().substringAfterLast(' ')
    }
}
