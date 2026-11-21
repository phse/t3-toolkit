package app.heckit.ide.t3toolkit.fluid

import app.heckit.ide.t3toolkit.T3Project
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.ArrayCreationExpression
import com.jetbrains.php.lang.psi.elements.ArrayHashElement
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression

/**
 * Extbase-Konvention: `FooController::listAction()` rendert `Resources/Private/Templates/Foo/List.html`
 * (auch in den per TypoScript ergänzten Template-Ordnern).
 */
object Extbase {
    fun isAction(method: Method): Boolean {
        val name = method.name
        val cls = method.containingClass ?: return false
        return name.endsWith("Action") && name.length > "Action".length && !name.startsWith("initialize") &&
            name != "errorAction" && cls.name.endsWith("Controller") && !cls.isAbstract && method.access.isPublic
    }

    /** Template-Dateien der Action. */
    fun templatesFor(project: Project, method: Method): List<VirtualFile> {
        if (!isAction(method)) return emptyList()
        val cls = method.containingClass ?: return emptyList()
        val controller = cls.name.removeSuffix("Controller")
        val action = method.name.removeSuffix("Action").replaceFirstChar(Char::uppercaseChar)
        val classFile = cls.containingFile?.virtualFile
        val own = classFile?.let { T3Project.get(project).extensionOf(it) }?.second
        // Ordner der eigenen Extension zuerst, dann die aus TypoScript
        val roots = LinkedHashSet<VirtualFile>()
        own?.findFileByRelativePath("Resources/Private/Templates")?.let(roots::add)
        T3Project.get(project).typoScriptRoots["Templates"].orEmpty().forEach(roots::add)
        return roots.mapNotNull { it.findFileByRelativePath("$controller/$action.html") }.distinct()
    }

    /** Actions, die das Template [template] (`Foo/List`) rendern - Controller derselben Extension zuerst. */
    fun actionsFor(project: Project, template: VirtualFile, templateName: String): List<Method> {
        val parts = templateName.split('/')
        if (parts.size < 2) return emptyList()
        val controller = parts.dropLast(1).joinToString("\\")
        val action = parts.last().replaceFirstChar(Char::lowercaseChar) + "Action"
        val t3 = T3Project.get(project)
        val templateExt = t3.extensionOf(template)?.second
        return PhpIndex.getInstance(project).getClassesByName(controller.substringAfterLast('\\') + "Controller")
            .mapNotNull { cls -> cls.findOwnMethodByName(action)?.takeIf { templatesFor(project, it).contains(template) } }
            .sortedBy { m -> if (templateExt != null && m.containingFile?.virtualFile?.let { VfsUtilCore.isAncestor(templateExt, it, false) } == true) 0 else 1 }
    }

    /** Namen, die eine Action per `assign()`/`assignMultiple()` an die View gibt. */
    fun assignedVariables(method: Method): List<String> {
        val result = LinkedHashSet<String>()
        PsiTreeUtil.findChildrenOfType(method, MethodReference::class.java).forEach { call ->
            when (call.name) {
                "assign" -> (call.parameters.firstOrNull() as? StringLiteralExpression)?.contents?.let(result::add)
                "assignMultiple" -> (call.parameters.firstOrNull() as? ArrayCreationExpression)?.let { arr ->
                    PsiTreeUtil.getChildrenOfTypeAsList(arr, ArrayHashElement::class.java).forEach { el ->
                        (el.key as? StringLiteralExpression)?.contents?.let(result::add)
                    }
                }
            }
        }
        return result.toList()
    }

    fun controllerOf(method: Method): PhpClass? = method.containingClass
}
