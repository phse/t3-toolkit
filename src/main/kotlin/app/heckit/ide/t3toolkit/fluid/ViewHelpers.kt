package app.heckit.ide.t3toolkit.fluid

import app.heckit.ide.t3toolkit.T3Project
import app.heckit.ide.t3toolkit.settings.T3Settings
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.ParameterList
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression

object ViewHelpers {
    /** Präfix → PHP-Namespaces in TYPO3-Reihenfolge: Vorgaben, global, eingestellt, Datei - der letzte gewinnt. */
    fun namespacesFor(file: PsiFile): Map<String, List<String>> {
        val project = file.project
        val settings = T3Settings.get(project)
        val result = LinkedHashMap<String, MutableList<String>>()
        fun add(prefix: String, ns: Collection<String>) = ns.forEach { n ->
            val list = result.getOrPut(prefix) { ArrayList() }
            if (n !in list) list += n
        }
        T3Project.DEFAULT_FLUID_NAMESPACES.forEach { (p, ns) -> add(p, ns) }
        if (settings.state.autoDetectNamespaces) T3Project.get(project).fluidNamespaces.forEach { (p, ns) -> add(p, ns) }
        settings.configuredNamespaces().forEach { (p, ns) -> add(p, ns) }
        Fluid.namespaces(file.viewProvider.contents).forEach { (p, ns) -> add(p, listOf(ns)) }
        return result
    }

    /** `format.html` → `Format\HtmlViewHelper`; wie TYPO3 gewinnt der zuletzt registrierte Namespace. */
    fun resolve(file: PsiFile, prefix: String, name: String): List<PhpClass> {
        val namespaces = namespacesFor(file)[prefix] ?: return emptyList()
        val segments = name.split('.').map { it.replaceFirstChar(Char::uppercaseChar) }
        val rel = segments.dropLast(1).joinToString("") { "$it\\" } + segments.last() + "ViewHelper"
        val index = PhpIndex.getInstance(file.project)
        return namespaces.asReversed().asSequence().map { index.getClassesByFQN("\\$it\\$rel") }.firstOrNull { it.isNotEmpty() }?.toList().orEmpty()
    }

    /** Stelle, an der der ViewHelper das Argument [name] registriert (auch in Elternklassen). */
    fun findArgument(cls: PhpClass, name: String): PsiElement? {
        var current: PhpClass? = cls
        val seen = HashSet<PhpClass>()
        while (current != null && seen.add(current)) {
            current.findOwnMethodByName("initializeArguments")?.let { method ->
                PsiTreeUtil.findChildrenOfType(method, StringLiteralExpression::class.java).firstOrNull { lit ->
                    lit.contents == name && (lit.parent as? ParameterList)?.let { list ->
                        list.parameters.firstOrNull() == lit &&
                            (list.parent as? MethodReference)?.name in setOf("registerArgument", "overrideArgument", "registerTagAttribute")
                    } == true
                }?.let { return it }
            }
            current.findOwnMethodByName("render")?.parameters?.firstOrNull { it.name == name }?.let { return it }
            current = current.superClass
        }
        return null
    }
}
