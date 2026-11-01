package app.heckit.ide.t3toolkit.services

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.PhpIndex
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/** Strg/⌘-Klick in Services.yaml: Klassen, `@service`-Verweise, `resource`/`exclude`-Pfade und `method` in Tags. */
class ServicesGotoHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(source: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val element = source ?: return null
        val vf = element.containingFile?.originalFile?.virtualFile
        if (!ServicesIndex.isServicesFile(vf)) return null
        val project = element.project
        val scalar = PsiTreeUtil.getParentOfType(element, YAMLScalar::class.java, false)
        val kv = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false) ?: return null
        val isKey = scalar == null && kv.key?.let { element.textRange.intersects(it.textRange) } == true
        val raw = when {
            scalar != null -> scalar.textValue
            isKey -> kv.keyText
            else -> return null
        }
        // Pfade
        val ownerKey = PsiTreeUtil.getParentOfType(scalar ?: return classOrService(project, raw), YAMLKeyValue::class.java)?.keyText
        if (ownerKey == "resource" || ownerKey == "exclude") {
            val target = vf!!.parent?.findFileByRelativePath(ServicesIndex.globBase(raw)) ?: return null
            val psi = if (target.isDirectory) PsiManager.getInstance(project).findDirectory(target) else PsiManager.getInstance(project).findFile(target)
            return psi?.let { arrayOf(it) }
        }
        if (ownerKey == "method") {
            val service = generateSequence(kv) { PsiTreeUtil.getParentOfType(it, YAMLKeyValue::class.java) }
                .firstOrNull { (it.parent?.parent as? YAMLKeyValue)?.keyText == "services" } ?: return null
            val cls = (service.value as? YAMLMapping)?.getKeyValueByKey("class")?.valueText ?: service.keyText
            return PhpIndex.getInstance(project).getAnyByFQN("\\" + ServicesIndex.normalize(cls))
                .mapNotNull { it.findMethodByName(raw) }.toTypedArray<PsiElement>()
        }
        return classOrService(project, raw)
    }

    private fun classOrService(project: com.intellij.openapi.project.Project, raw: String): Array<PsiElement>? {
        val name = ServicesIndex.normalize(raw)
        if (ServicesIndex.CLASS_NAME.matches(name)) {
            val classes = PhpIndex.getInstance(project).getAnyByFQN("\\" + name)
            if (classes.isNotEmpty()) return classes.toTypedArray()
        }
        if (raw.trim('\'', '"').startsWith("@")) {
            ServicesIndex.get(project).byId[name]?.let { return arrayOf(it) }
        }
        return null
    }
}
