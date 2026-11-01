package app.heckit.ide.t3toolkit

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import javax.swing.Icon

/**
 * Sprungziel mit lesbarer Beschriftung im „Choose Declaration“-Popup: statt nur `'teasers'`
 * z. B. `TeaserProcessor::process()` mit `EXT:my_ext/…/TeaserProcessor.php:63`.
 */
class NavTarget private constructor(private val target: PsiElement, private val label: String, private val location: String) : FakePsiElement() {
    override fun getParent(): PsiElement = target
    override fun getNavigationElement(): PsiElement = target
    override fun getPresentableText() = label
    override fun getLocationString() = location
    override fun getIcon(open: Boolean): Icon? = target.containingFile?.getIcon(0)
    override fun getName() = label
    override fun getText(): String = target.text
    override fun getTextOffset() = target.textRange.startOffset
    override fun canNavigate() = true
    override fun navigate(requestFocus: Boolean) {
        val vf = target.containingFile?.virtualFile ?: return
        OpenFileDescriptor(target.project, vf, target.textRange.startOffset).navigate(requestFocus)
    }

    companion object {
        fun of(element: PsiElement): NavTarget {
            val p = UsageRenderer.presentation(element)
            val method = PsiTreeUtil.getParentOfType(element, Method::class.java)
            val cls = PsiTreeUtil.getParentOfType(element, PhpClass::class.java)
            val label = when {
                method != null && cls != null -> "${cls.name}::${method.name}()"
                cls != null -> cls.name
                else -> p.presentableText
            }
            return NavTarget(element, label, p.locationText.orEmpty())
        }
    }
}
