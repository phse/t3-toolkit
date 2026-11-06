package app.heckit.ide.t3toolkit.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/** Projekt-Einstellungen, abgelegt in `.idea/t3toolkit.xml` (kann ins Team-Repo). */
@Service(Service.Level.PROJECT)
@State(name = "T3Toolkit", storages = [Storage("t3toolkit.xml")])
class T3Settings : PersistentStateComponent<T3Settings.Data> {
    class Data {
        /** Globale Fluid-Namespaces aus ext_localconf.php und TYPO3-Vorgaben übernehmen. */
        var autoDetectNamespaces: Boolean = true
        /** Eine Zeile je Präfix: `prefix = Vendor\Ext\ViewHelpers, Other\ViewHelpers` */
        var namespaces: String = ""
        /** Eigene Übersetzungs-Aufrufe, eine Zeile je Aufruf (siehe TranslationCall). */
        var translationCalls: String = ""
    }

    private var data = Data()
    override fun getState() = data
    override fun loadState(state: Data) { XmlSerializerUtil.copyBean(state, data) }

    var modificationCount = 0L
        private set

    fun update(autoDetect: Boolean, namespaces: String, translationCalls: String) {
        data.autoDetectNamespaces = autoDetect
        data.namespaces = namespaces
        data.translationCalls = translationCalls
        modificationCount++
    }

    /** Eigene Namespaces als Präfix → Liste. */
    fun configuredNamespaces(): Map<String, List<String>> = parse(data.namespaces)

    companion object {
        fun get(project: Project): T3Settings = project.getService(T3Settings::class.java)

        fun parse(text: String): Map<String, List<String>> = text.lines()
            .map { it.substringBefore('#').trim() }
            .filter { '=' in it }
            .associate { line ->
                line.substringBefore('=').trim() to
                    line.substringAfter('=').split(',', ' ').map { it.trim().trim('\\') }.filter { it.isNotEmpty() }
            }
            .filterKeys { it.matches(Regex("[A-Za-z0-9_]+")) }
    }
}
