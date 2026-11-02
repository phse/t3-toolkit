package app.heckit.ide.t3toolkit.fluid

import app.heckit.ide.t3toolkit.UsageRenderer
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.codeInsight.navigation.impl.PsiTargetPresentationRenderer
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlTokenType
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.templateLanguages.OuterLanguageElement
import com.intellij.psi.util.PsiTreeUtil

/** Gutter-Icon in der ersten Zeile von Partials, Layouts, Templates und Components: wo wird die Datei verwendet? */
class FluidUsagesLineMarker : RelatedItemLineMarkerProvider() {
    private fun tooltip(what: String, name: String, usages: List<PsiElement>): String {
        val head = "<b>$what</b> ${StringUtil.escapeXmlEntities(name)}<br>"
        if (usages.isEmpty()) return "<html>${head}not used anywhere</html>"
        val max = 8
        val lines = usages.take(max).joinToString("<br>") { "&nbsp;&nbsp;" + StringUtil.escapeXmlEntities(UsageRenderer.presentation(it).locationText.orEmpty()) }
        val more = if (usages.size > max) "<br>&nbsp;&nbsp;… ${usages.size - max} more" else ""
        return "<html>${head}used in ${usages.size} place${if (usages.size == 1) "" else "s"}:<br>$lines$more</html>"
    }

    private fun anchor(file: PsiFile): PsiElement? {
        val provider = file.viewProvider
        // Components: am Tag <fc:component>, dort steht die Definition
        if ("<fc:component" in provider.contents) {
            provider.allFiles.filterIsInstance<XmlFile>().firstNotNullOfOrNull { root ->
                PsiTreeUtil.findChildrenOfType(root, XmlTag::class.java).firstOrNull { it.name == "fc:component" }
                    ?.node?.findChildByType(XmlTokenType.XML_NAME)?.psi
            }?.let { return it }
        }
        return provider.allFiles.mapNotNull { root ->
            var leaf = PsiTreeUtil.firstChild(root).takeIf { it.firstChild == null }
            while (leaf != null && (leaf is PsiWhiteSpace || leaf is OuterLanguageElement || leaf.textLength == 0)) leaf = PsiTreeUtil.nextLeaf(leaf)
            leaf
        }.minWithOrNull(compareBy<PsiElement> { it.textRange.startOffset }.thenBy { if (it.containingFile.language.id == "HTML") 0 else 1 })
    }

    override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
        if (element.firstChild != null || element is PsiWhiteSpace || element is OuterLanguageElement) return
        val file = element.containingFile ?: return
        // Das Fluid-Plugin injiziert Fluid in die HTML-Datei ({namespace …} ist dann ein eigenes Fragment, dessen erstes
        // Blatt ebenfalls ganz vorn steht) - nur die Host-Datei auswerten, sonst erscheint das Icon doppelt.
        if (InjectedLanguageManager.getInstance(element.project).isInjectedFragment(file)) return
        // Genau ein Anker je Datei: das erste echte Blatt über alle PSI-Wurzeln (Template-Sprachen haben mehrere).
        val anchor = CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(anchor(file), PsiModificationTracker.MODIFICATION_COUNT)
        }
        if (element != anchor) return
        val vf = file.virtualFile ?: return
        if (vf.extension?.lowercase() != "html") return
        val project = element.project
        val role = Fluid.roleOf(project, vf, file.viewProvider.contents) ?: return
        if (role.kind == "Component") relatedMarker(element, vf)?.let(result::add)
        val usages = Fluid.usages(project, vf, role)
        val what = when (role.kind) {
            "Partials" -> "Partial"
            "Layouts" -> "Layout"
            "Templates" -> "Template"
            else -> "Component"
        }
        val builder = NavigationGutterIconBuilder.create(app.heckit.ide.t3toolkit.T3Icons.GUTTER)
            .setTargets(usages)
            .setTooltipText(tooltip(what, role.name, usages))
            .setPopupTitle("Usages of $what '${role.name}'")
            .setEmptyPopupText("No usages of $what '${role.name}' found")
            .setTargetRenderer {
                object : PsiTargetPresentationRenderer<PsiElement>() {
                    override fun getPresentation(element: PsiElement): TargetPresentation = UsageRenderer.presentation(element)
                }
            }
        // Eigene Klasse: Die Standard-Info listet sich im Popup mit dem Text des Anker-Elements („{namespace“)
        val info = builder.createLineMarkerInfo(element)
        result += object : RelatedItemLineMarkerInfo<PsiElement>(
            element, element.textRange, info.icon, { info.lineMarkerTooltip }, info.navigationHandler,
            GutterIconRenderer.Alignment.RIGHT, { info.createGotoRelatedItems() },
        ) {
            override fun getElementPresentation(element: PsiElement) = "$what ${role.name}"
            // Nicht mit anderen Markern der Zeile zu einem Auswahl-Popup zusammenfassen
            override fun canMergeWith(info: com.intellij.codeInsight.daemon.MergeableLineMarkerInfo<*>) = false
        }
    }

    /** Zweites Icon an `<fc:component>`: Fixture, Styles, Scripts … der Component. */
    private fun relatedMarker(element: PsiElement, vf: com.intellij.openapi.vfs.VirtualFile): RelatedItemLineMarkerInfo<*>? {
        val psiManager = com.intellij.psi.PsiManager.getInstance(element.project)
        val targets = Fluid.componentRelated(vf).mapNotNull { psiManager.findFile(it) }
        if (targets.isEmpty()) return null
        val names = targets.joinToString("<br>") { "&nbsp;&nbsp;" + StringUtil.escapeXmlEntities(it.virtualFile.path.substringAfter(vf.parent.path + "/")) }
        val info = NavigationGutterIconBuilder.create(AllIcons.Nodes.Folder)
            .setTargets(targets)
            .setTooltipText("<html><b>Component files</b><br>$names</html>")
            .setPopupTitle("Files of Component ${vf.nameWithoutExtension}")
            .createLineMarkerInfo(element)
        return object : RelatedItemLineMarkerInfo<PsiElement>(
            element, element.textRange, info.icon, { info.lineMarkerTooltip }, info.navigationHandler,
            GutterIconRenderer.Alignment.RIGHT, { info.createGotoRelatedItems() },
        ) {
            override fun getElementPresentation(element: PsiElement) = "Component files"
            override fun canMergeWith(info: com.intellij.codeInsight.daemon.MergeableLineMarkerInfo<*>) = false
        }
    }
}
