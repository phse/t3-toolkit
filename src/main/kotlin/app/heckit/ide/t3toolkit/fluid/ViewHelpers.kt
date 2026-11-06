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

/** Ein Argument eines ViewHelpers aus `registerArgument()` (bzw. `render()`-Parameter in alten ViewHelpern). */
data class VhArgument(
    val name: String, val type: String?, val description: String?, val required: Boolean, val default: String?,
    val element: PsiElement,
)

object ViewHelpers {
    private const val ABSTRACT_VH = "\\TYPO3Fluid\\Fluid\\Core\\ViewHelper\\AbstractViewHelper"
    private const val TAG_BASED = "\\TYPO3Fluid\\Fluid\\Core\\ViewHelper\\AbstractTagBasedViewHelper"
    private val REGISTER = setOf("registerArgument", "overrideArgument", "registerTagAttribute")
    /** Was AbstractTagBasedViewHelper über registerUniversalTagAttributes() bzw. ab v13 immer erlaubt. */
    private val UNIVERSAL = listOf("class", "id", "style", "title", "lang", "dir", "accesskey", "tabindex", "onclick", "data", "aria", "additionalAttributes")

    /** Alle ViewHelper-Klassen des Projekts (nicht abstrakt), gecacht bis zur nächsten PSI-Änderung. */
    private fun allClasses(project: com.intellij.openapi.project.Project): List<PhpClass> =
        com.intellij.psi.util.CachedValuesManager.getManager(project).getCachedValue(project) {
            val index = PhpIndex.getInstance(project)
            val all = ArrayList<PhpClass>()
            index.processAllSubclasses(ABSTRACT_VH) { if (!it.isAbstract && !it.isInterface) all += it; true }
            com.intellij.psi.util.CachedValueProvider.Result.create(all, com.intellij.psi.util.PsiModificationTracker.MODIFICATION_COUNT)
        }

    /** Alle ViewHelper unter [prefix]: Fluid-Name (`format.html`) → Klasse; spätere Namespaces überschreiben frühere. */
    fun all(file: PsiFile, prefix: String): Map<String, PhpClass> {
        val namespaces = namespacesFor(file)[prefix] ?: return emptyMap()
        val classes = allClasses(file.project)
        val result = java.util.TreeMap<String, PhpClass>()
        for (ns in namespaces) {
            val base = "\\$ns\\"
            classes.forEach { cls ->
                val fqn = cls.fqn
                if (!fqn.startsWith(base) || !fqn.endsWith("ViewHelper")) return@forEach
                val name = fqn.removePrefix(base).removeSuffix("ViewHelper").split('\\')
                    .joinToString(".") { it.replaceFirstChar(Char::lowercaseChar) }
                if (name.isNotEmpty()) result[name] = cls
            }
        }
        return result
    }

    fun isTagBased(cls: PhpClass): Boolean {
        var c: PhpClass? = cls
        val seen = HashSet<PhpClass>()
        while (c != null && seen.add(c)) {
            if (c.fqn == TAG_BASED) return true
            c = c.superClass
        }
        return false
    }

    /** Argumente in der Reihenfolge der Registrierung; Kindklassen überschreiben Elternklassen. */
    fun arguments(cls: PhpClass): List<VhArgument> {
        val chain = ArrayList<PhpClass>()
        var c: PhpClass? = cls
        while (c != null && c !in chain) { chain += c; c = c.superClass }
        val result = LinkedHashMap<String, VhArgument>()
        for (k in chain.asReversed()) {
            k.findOwnMethodByName("initializeArguments")?.let { method ->
                PsiTreeUtil.findChildrenOfType(method, MethodReference::class.java).filter { it.name in REGISTER }.forEach { call ->
                    val params = call.parameters
                    val name = (params.getOrNull(0) as? StringLiteralExpression)?.contents ?: return@forEach
                    fun str(i: Int) = (params.getOrNull(i) as? StringLiteralExpression)?.contents
                    val tagAttr = call.name == "registerTagAttribute"
                    val previous = result[name]
                    result[name] = VhArgument(
                        name,
                        str(1) ?: previous?.type,
                        str(2) ?: previous?.description,
                        params.getOrNull(3)?.text?.lowercase() == "true",
                        params.getOrNull(if (tagAttr) 4 else 4)?.text?.takeIf { it.lowercase() != "null" },
                        params[0],
                    )
                }
            }
            k.findOwnMethodByName("render")?.parameters?.forEach { p ->
                result[p.name] = VhArgument(p.name, p.declaredType.toString().trimStart('\\').ifEmpty { null }, null,
                    p.defaultValue == null, p.defaultValue?.text, p)
            }
        }
        return result.values.toList()
    }

    /** Argumente plus die allgemeinen HTML-Attribute tag-basierter ViewHelper (ohne Element, nie Pflicht). */
    fun attributeNames(cls: PhpClass): List<String> =
        arguments(cls).map { it.name } + if (isTagBased(cls)) UNIVERSAL else emptyList()

    /** Beschreibung aus dem DocBlock der Klasse (erster Absatz-Block, ohne Tags), als reiner Text. */
    fun description(cls: PhpClass): String? {
        val doc = cls.docComment?.text ?: return null
        val lines = doc.lines().map { it.trim().removePrefix("/**").removeSuffix("*/").trim().removePrefix("*").trim() }
        val text = lines.takeWhile { !it.startsWith("@") }.joinToString("\n").trim()
        return text.ifEmpty { null }
    }

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
