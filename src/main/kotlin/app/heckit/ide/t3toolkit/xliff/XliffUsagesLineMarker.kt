package app.heckit.ide.t3toolkit.xliff

import app.heckit.ide.t3toolkit.T3Icons
import app.heckit.ide.t3toolkit.UsageRenderer
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.codeInsight.navigation.impl.PsiTargetPresentationRenderer
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTokenType

/** Gutter-Icon an jedem `<trans-unit id="…">`: wo wird das Label verwendet? */
class XliffUsagesLineMarker : RelatedItemLineMarkerProvider() {
    override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
        if (element.node?.elementType != XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN) return
        val attr = element.parent?.parent as? XmlAttribute ?: return
        if (attr.name != "id" || attr.parent?.name != "trans-unit") return
        val vf = element.containingFile?.virtualFile ?: return
        if (vf.extension?.lowercase() != "xlf") return
        val project = element.project
        val key = element.text
        val usages = Xliff.usages(project, Xliff.defaultOf(vf))[key].orEmpty()
        val head = "<b>Label</b> ${StringUtil.escapeXmlEntities(key)}<br>"
        val tooltip = if (usages.isEmpty()) "<html>${head}not used anywhere</html>" else {
            val lines = usages.take(8).joinToString("<br>") { "&nbsp;&nbsp;" + StringUtil.escapeXmlEntities(UsageRenderer.presentation(it).locationText.orEmpty()) }
            val more = if (usages.size > 8) "<br>&nbsp;&nbsp;… ${usages.size - 8} more" else ""
            "<html>${head}used in ${usages.size} place${if (usages.size == 1) "" else "s"}:<br>$lines$more</html>"
        }
        val info = NavigationGutterIconBuilder.create(if (usages.isEmpty()) app.heckit.ide.t3toolkit.T3Icons.GUTTER_UNUSED else app.heckit.ide.t3toolkit.T3Icons.GUTTER)
            .setTargets(usages)
            .setTooltipText(tooltip)
            .setPopupTitle("Usages of Label '$key'")
            .setEmptyPopupText("No usages of label '$key' found")
            .setTargetRenderer {
                object : PsiTargetPresentationRenderer<PsiElement>() {
                    override fun getPresentation(element: PsiElement): TargetPresentation = UsageRenderer.presentation(element)
                }
            }
            .createLineMarkerInfo(element)
        result += object : RelatedItemLineMarkerInfo<PsiElement>(
            element, element.textRange, info.icon, { info.lineMarkerTooltip }, info.navigationHandler,
            GutterIconRenderer.Alignment.RIGHT, { info.createGotoRelatedItems() },
        ) {
            override fun getElementPresentation(element: PsiElement) = "Label $key"
            override fun canMergeWith(info: com.intellij.codeInsight.daemon.MergeableLineMarkerInfo<*>) = false
        }
    }
}
