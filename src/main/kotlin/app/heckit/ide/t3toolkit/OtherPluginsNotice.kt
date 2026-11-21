package app.heckit.ide.t3toolkit

import com.intellij.ide.util.PropertiesComponent
import com.intellij.lang.Language
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Einmaliger Hinweis, wenn neben T3 Toolkit noch andere TypoScript- bzw. Fluid-Plugins laufen: beide registrieren die
 * Endung `.typoscript` und färben Fluid-Ausdrücke - das führt zu Konflikten und doppelten Vorschlägen.
 */
class OtherPluginsNotice : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (System.getProperty("t3toolkit.screenshots") != null) return
        val others = buildList {
            if (Language.findLanguageByID("TYPO3_TypoScript") != null) add("TypoScript")
            if (Language.findLanguageByID("FLUID") != null) add("Fluid")
        }
        if (others.isEmpty()) return
        val props = PropertiesComponent.getInstance()
        val key = "t3toolkit.otherPluginsNotice." + others.joinToString("+")
        if (props.getBoolean(key)) return
        props.setValue(key, true)
        NotificationGroupManager.getInstance().getNotificationGroup("T3 Toolkit")
            .createNotification(
                "T3 Toolkit now covers ${others.joinToString(" and ")}",
                "Another ${others.joinToString("/")} plugin is installed as well. Both handle the same files, which leads to " +
                    "file type conflicts and duplicate highlighting or completion. Disable one of them in Settings | Plugins.",
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimpleExpiring("Open Plugins") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "Plugins")
            })
            .notify(project)
    }
}
