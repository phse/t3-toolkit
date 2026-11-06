package app.heckit.ide.t3toolkit.screenshot

import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.intention.impl.ShowIntentionActionsHandler
import com.intellij.ide.ui.LafManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.PsiDocumentManager
import java.awt.Window
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * Erzeugt die Marketplace-Screenshots in der Sandbox-IDE (./gradlew runIde -Pscreenshots=...).
 * Zeichnet Frame und offene Popups selbst in ein Bild, braucht also keine Bildschirmaufnahme-Rechte.
 * Ist nur in Builds mit -Pscreenshots enthalten.
 */
object ScreenshotMaker {
    private const val SCALE = 2.0
    private const val EXT = "packages/demo_site"
    private val log = logger<ScreenshotMaker>()

    @JvmStatic
    fun start(project: Project, dir: String) {
        val out = File(dir).apply { mkdirs() }
        Thread({
            try {
                run(project, out)
            } catch (e: Throwable) {
                log.error("Screenshots failed", e)
            }
        }, "T3 Toolkit screenshots").start()
    }

    private fun run(project: Project, out: File) {
        DumbService.getInstance(project).waitForSmartMode()
        val frame = edt { WindowManager.getInstance().getFrame(project) } ?: error("no frame")
        val laf = LafManager.getInstance()
        val previousTheme = laf.currentUIThemeLookAndFeel
        edt {
            theme(laf, dark = false)
            frame.extendedState = JFrame.NORMAL
            frame.setBounds(60, 40, 1440, 860)
        }
        Thread.sleep(3000)

        // 1. Partial: Verwendungen im Gutter
        partialUsages(project, frame, File(out, "01-partial-usages.png"))
        // 3. PHP-Klasse: Einträge in Services.yaml
        val php = open(project, "Classes/EventListener/AddTeaserData.php")
        Thread.sleep(2500)
        clickGutter(project, php, php.document.text.indexOf("AddTeaserData"))
        Thread.sleep(1500)
        tidy(project, frame)
        Thread.sleep(800)
        shot(frame, File(out, "02-services-gutter.png"))
        edt { closePopups() }
        // 4. Services.yaml: Quick-Fix zum PHP-Attribut
        val yaml = open(project, "Configuration/Services.yaml")
        Thread.sleep(2000)
        tidy(project, frame)
        Thread.sleep(3500)
        edt {
            yaml.caretModel.moveToOffset(yaml.document.text.indexOf("Acme\\DemoSite\\EventListener") + 5)
            val psi = PsiDocumentManager.getInstance(project).getPsiFile(yaml.document)!!
            ShowIntentionActionsHandler().invoke(project, yaml, psi)
        }
        Thread.sleep(2000)
        tidy(project, frame)
        Thread.sleep(800)
        shot(frame, File(out, "03-attribute-quickfix.png"))
        edt { closePopups() }
        // 5. Labels: Verwendungen eines trans-unit
        val xlf = open(project, "Resources/Private/Language/locallang.xlf")
        Thread.sleep(3000)
        tidy(project, frame)
        Thread.sleep(800)
        clickGutter(project, xlf, xlf.document.text.indexOf("teaser.more"))
        Thread.sleep(2500)
        shot(frame, File(out, "05-label-usages.png"))
        edt { closePopups() }
        // 6. Labels im PHP-Code eingeklappt, fehlender Key als Fehler
        open(project, "Configuration/TCA/Overrides/tt_content.php")
        Thread.sleep(4000)
        tidy(project, frame)
        Thread.sleep(800)
        shot(frame, File(out, "06-label-folding.png"))
        // 4. Dunkel: Services.yaml-Einträge an der PHP-Klasse
        edt { theme(laf, dark = true) }
        Thread.sleep(2500)
        val php2 = open(project, "Classes/EventListener/AddTeaserData.php")
        Thread.sleep(1500)
        tidy(project, frame)
        clickGutter(project, php2, php2.document.text.indexOf("AddTeaserData"))
        Thread.sleep(2000)
        shot(frame, File(out, "04-dark.png"))
        edt { closePopups() }

        edt {
            previousTheme?.let { laf.setCurrentLookAndFeel(it, false); laf.updateUI() }
        }
        log.warn("T3 Toolkit: screenshots written to $out")
    }

    private fun partialUsages(project: Project, frame: JFrame, file: File) {
        val card = open(project, "Resources/Private/Partials/Teaser/Card.html")
        Thread.sleep(3000)
        tidy(project, frame)
        Thread.sleep(800)
        clickGutter(project, card, 0)
        Thread.sleep(3000)
        shot(frame, file)
        edt { closePopups() }
    }

    private fun open(project: Project, path: String): Editor = edt {
        val vf = project.guessProjectDir()?.findFileByRelativePath("$EXT/$path") ?: error("$path missing")
        com.intellij.ide.projectView.ProjectView.getInstance(project).select(null, vf, false)
        FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, vf, 0), true) ?: error("no editor")
    }

    /** Klickt auf den T3-Toolkit-Marker der Zeile von [offset] (öffnet das Verwendungs-Popup). */
    private fun clickGutter(project: Project, editor: Editor, offset: Int) = edt {
        val line = editor.document.getLineNumber(offset)
        val marker = DaemonCodeAnalyzerImpl.getLineMarkers(editor.document, project)
            .firstOrNull { editor.document.getLineNumber(it.startOffset) == line && it.icon?.toString()?.contains("t3Gutter") == true }
            ?: DaemonCodeAnalyzerImpl.getLineMarkers(editor.document, project).firstOrNull { editor.document.getLineNumber(it.startOffset) == line }
            ?: error("no marker in line $line")
        log.warn("T3 Toolkit screenshot: marker ${marker.lineMarkerTooltip?.take(60)} handler=${marker.navigationHandler}")
        val gutter = (editor as EditorEx).gutterComponentEx
        val y = editor.logicalPositionToXY(editor.offsetToLogicalPosition(offset)).y + editor.lineHeight / 2
        val event = MouseEvent(gutter, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, gutter.width - 10, y, 1, false)
        @Suppress("UNCHECKED_CAST")
        (marker.navigationHandler as com.intellij.codeInsight.daemon.GutterIconNavigationHandler<com.intellij.psi.PsiElement>?)
            ?.navigate(event, marker.element)
    }

    private fun closePopups() {
        generateSequence(JBPopupFactory.getInstance().getChildFocusedPopup(null)) { null }.forEach { it.cancel() }
        Window.getWindows().filter { it.isVisible && it !is JFrame && it.owner != null && it.type == Window.Type.POPUP }.forEach { it.dispose() }
    }

    private fun theme(laf: LafManager, dark: Boolean) {
        val target = laf.installedThemes.firstOrNull { it.isDark == dark } ?: return
        if (laf.currentUIThemeLookAndFeel?.isDark == dark) return
        laf.setCurrentLookAndFeel(target, false)
        laf.updateUI()
    }

    /** Frame plus darüberliegende Popup-Fenster (Lookup, Gutter-Popup, Intentions) in ein Bild. */
    private fun tidy(project: Project, frame: JFrame) = edt {
        com.intellij.notification.NotificationsManager.getNotificationsManager()
            .getNotificationsOfType(com.intellij.notification.Notification::class.java, project).forEach { it.expire() }
        fun walk(c: java.awt.Component) {
            if (c is com.intellij.ui.HyperlinkLabel && c.text.contains("Not a Compose", true)) c.doClick()
            if (c is com.intellij.ui.EditorNotificationPanel) c.isVisible = false
            if (c is java.awt.Container) c.components.forEach(::walk)
        }
        walk(frame.rootPane)
    }

    private fun shot(frame: JFrame, file: File) = edt {
        val root = frame.rootPane
        val fr = java.awt.Rectangle(root.locationOnScreen, root.size)
        Window.getWindows().filter { it.isVisible && it !== frame && it.isShowing && it.width > 0 }.forEach { w ->
            val x = w.x.coerceIn(fr.x + 20, maxOf(fr.x + 20, fr.x + fr.width - w.width - 20))
            if (x != w.x) w.setLocation(x, w.y)
        }
        val image = BufferedImage((root.width * SCALE).toInt(), (root.height * SCALE).toInt(), BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.scale(SCALE, SCALE)
        root.paint(g)
        val origin = root.locationOnScreen
        Window.getWindows().filter { it.isVisible && it !== frame && it.isShowing && it.width > 0 }.forEach { w ->
            val p = w.locationOnScreen
            val wg = g.create(p.x - origin.x, p.y - origin.y, w.width, w.height)
            (SwingUtilities.getRootPane(w) ?: w).paint(wg)
            wg.dispose()
        }
        g.dispose()
        ImageIO.write(image, "png", file)
    }

    private fun <T> edt(block: () -> T): T {
        var result: Result<T>? = null
        ApplicationManager.getApplication().invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
