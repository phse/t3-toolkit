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
        }
    }

    override fun apply() {
        super.apply()
        settings.update(autoDetect, namespaces)
    }
}
