package app.heckit.ide.t3toolkit.settings

import app.heckit.ide.t3toolkit.T3Project
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows

/** Settings | Languages & Frameworks | T3 Toolkit */
class T3Configurable(private val project: Project) : BoundConfigurable("T3 Toolkit") {
    private val settings = T3Settings.get(project)
    private var autoDetect = settings.state.autoDetectNamespaces
    private var namespaces = settings.state.namespaces
    private var translationCalls = settings.state.translationCalls

    override fun createPanel(): DialogPanel {
        val detected = T3Project.DEFAULT_FLUID_NAMESPACES + T3Project.get(project).fluidNamespaces
        val detectedText = if (detected.isEmpty()) "none"
        else detected.entries.joinToString("<br>") { (p, ns) -> "<code>$p</code> = " + ns.joinToString(", ") { StringUtil.escapeXmlEntities(it) } }
        return panel {
            group("Fluid Namespaces") {
                row {
                    checkBox("Detect namespaces registered in ext_localconf.php (\$GLOBALS['TYPO3_CONF_VARS']['SYS']['fluid']['namespaces'])")
                        .bindSelected(::autoDetect)
                }
                row { comment("TYPO3 defaults and detected:<br>$detectedText") }
                row {
                    textArea().bindText(::namespaces).rows(6).align(AlignX.FILL)
                        .label("Additional:", com.intellij.ui.dsl.builder.LabelPosition.TOP)
                        .comment("Additional namespaces, one prefix per line: <code>my = Vendor\\Ext\\ViewHelpers, Vendor\\Other\\ViewHelpers</code>. " +
                            "They are used for ViewHelpers and Fluid Components in every template, on top of <code>{namespace …}</code> and <code>xmlns:…</code> in the file.")
                }
            }
            group("Translations") {
                row {
                    comment("Built in: " + app.heckit.ide.t3toolkit.xliff.TranslationCall.DEFAULTS.lines().joinToString(", ") { "<code>${StringUtil.escapeXmlEntities(it)}</code>" } +
                        ", and <code>LLL:EXT:…</code> in every file.")
                }
                row {
                    textArea().bindText(::translationCalls).rows(5).align(AlignX.FILL)
                        .label("Additional calls with a label key:", com.intellij.ui.dsl.builder.LabelPosition.TOP)
                        .comment("One per line, arguments in brackets: <code>key</code> = label key, <code>extension…</code> = extension " +
                            "(key or UpperCamelCase), <code>_</code> = skip, alternatives with <code>|</code>.<br>" +
                            "ViewHelper (attribute names, prefix <code>*</code> = any): <code>my:label(key|id, extensionName)</code><br>" +
                            "PHP (position or named argument): <code>MyUtility::translate(key, extension)</code>, <code>-&gt;label(key)</code>, " +
                            "<code>my_translate(_, key)</code><br>" +
                            "Without an extension the label is looked up in <code>locallang.xlf</code> of the own extension, then of all others.")
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        settings.update(autoDetect, namespaces, translationCalls)
    }
}
