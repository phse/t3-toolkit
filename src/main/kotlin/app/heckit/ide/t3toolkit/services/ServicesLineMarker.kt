package app.heckit.ide.t3toolkit.services

import app.heckit.ide.t3toolkit.UsageRenderer
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.codeInsight.navigation.impl.PsiTargetPresentationRenderer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement
import com.jetbrains.php.lang.psi.elements.PhpClass

/** Gutter-Icon an PHP-Klassen: in welcher Services.yaml und als was ist die Klasse registriert? */
class ServicesLineMarker : RelatedItemLineMarkerProvider() {
    override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
        if (element.firstChild != null) return
        val cls = element.parent as? PhpClass ?: return
        if (cls.nameIdentifier != element) return
        val usages = ServicesIndex.get(element.project).usagesOf(cls.fqn.trimStart('\\'), cls.containingFile?.virtualFile)
        if (usages.isEmpty()) return
        val labels = usages.associate { it.element to it.label }
        val tooltip = "<html><b>Services.yaml</b><br>" + usages.joinToString("<br>") { StringUtil.escapeXmlEntities(it.label) } + "</html>"
        result += NavigationGutterIconBuilder.create(app.heckit.ide.t3toolkit.T3Icons.GUTTER)
            .setTargets(usages.map { it.element })
            .setTooltipText(tooltip)
            .setPopupTitle("Services.yaml entries of ${cls.name}")
            .setTargetRenderer {
                object : PsiTargetPresentationRenderer<PsiElement>() {
                    override fun getPresentation(element: PsiElement): TargetPresentation {
                        val base = UsageRenderer.presentation(element)
                        return TargetPresentation.builder(labels[element] ?: base.presentableText)
                            .icon(AllIcons.FileTypes.Yaml)
                            .locationText(base.locationText)
                            .presentation()
                    }
                }
            }
            .createLineMarkerInfo(element)
    }
}
