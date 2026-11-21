package app.heckit.ide.t3toolkit.fluid

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.jetbrains.php.lang.psi.elements.Method

/** Gutter-Icon an Extbase-Actions (`listAction()`): das Fluid-Template der Action. */
class ExtbaseLineMarker : RelatedItemLineMarkerProvider() {
    override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
        if (element.firstChild != null) return
        val method = element.parent as? Method ?: return
        if (method.nameIdentifier != element || !Extbase.isAction(method)) return
        val project = element.project
        val templates = Extbase.templatesFor(project, method).mapNotNull { PsiManager.getInstance(project).findFile(it) }
        if (templates.isEmpty()) return
        val t3 = app.heckit.ide.t3toolkit.T3Project.get(project)
        val names = templates.joinToString("<br>") { StringUtil.escapeXmlEntities(t3.extPathOf(it.virtualFile) ?: it.name) }
        result += NavigationGutterIconBuilder.create(app.heckit.ide.t3toolkit.T3Icons.GUTTER)
            .setTargets(templates)
            .setTooltipText("<html><b>Fluid template</b><br>$names</html>")
            .setPopupTitle("Templates of ${method.containingClass?.name}::${method.name}()")
            .createLineMarkerInfo(element)
    }
}
