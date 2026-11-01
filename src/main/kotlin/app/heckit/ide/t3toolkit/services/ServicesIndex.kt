package app.heckit.ide.t3toolkit.services

import app.heckit.ide.t3toolkit.T3Project
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLValue

/** Eine Stelle in einer Services.yaml, die eine Klasse betrifft. */
data class ServiceUsage(val element: PsiElement, val label: String, val file: VirtualFile)

/** Autodiscovery-Eintrag (Prefix mit resource-Pfad, z. B. ../Classes). */
data class ServicePrefix(val prefix: String, val base: VirtualFile, val excludes: List<VirtualFile>, val element: PsiElement, val file: VirtualFile)

class ServicesIndex private constructor(
    val byClass: Map<String, List<ServiceUsage>>,
    val byId: Map<String, YAMLKeyValue>,
    val prefixes: List<ServicePrefix>,
) {
    /** Alle Einträge zur Klasse [fqn] in [classFile], einschließlich Autodiscovery. */
    fun usagesOf(fqn: String, classFile: VirtualFile?): List<ServiceUsage> {
        val result = ArrayList(byClass[fqn].orEmpty())
        if (classFile != null) {
            prefixes.filter { fqn.startsWith(it.prefix) && VfsUtilCore.isAncestor(it.base, classFile, false) }
                .filter { p -> p.excludes.none { VfsUtilCore.isAncestor(it, classFile, false) } }
                .forEach { result += ServiceUsage(it.element, "autodiscovered via '${it.prefix}'", it.file) }
        }
        return result
    }

    companion object {
        val CLASS_NAME = Regex("""^[A-Za-z_][A-Za-z0-9_]*(\\[A-Za-z_][A-Za-z0-9_]*)+$""")
        private val FLAGS = listOf("public", "shared", "autowire", "autoconfigure", "lazy", "abstract")

        fun isServicesFile(file: VirtualFile?): Boolean =
            file != null && file.name.lowercase() in setOf("services.yaml", "services.yml")

        /** `'@?Vendor\\Foo'` → `Vendor\Foo` */
        fun normalize(text: String): String = text.trim().trim('\'', '"').removePrefix("@").removePrefix("?")
            .replace("\\\\", "\\").trimStart('\\')

        fun get(project: Project): ServicesIndex = CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(build(project), PsiModificationTracker.MODIFICATION_COUNT)
        }

        private fun build(project: Project): ServicesIndex {
            val scope = GlobalSearchScope.allScope(project)
            val byClass = HashMap<String, MutableList<ServiceUsage>>()
            val byId = HashMap<String, YAMLKeyValue>()
            val prefixes = ArrayList<ServicePrefix>()
            val psiManager = PsiManager.getInstance(project)
            val files = listOf("Services.yaml", "services.yaml", "Services.yml", "services.yml")
                .flatMap { FilenameIndex.getVirtualFilesByName(it, scope) }.distinct()
            for (vf in files) {
                val yaml = psiManager.findFile(vf) as? YAMLFile ?: continue
                val ext = T3Project.get(project).extensionOf(vf)?.first
                val where = if (ext != null) "EXT:$ext" else vf.parent?.parent?.name ?: vf.name
                for (doc in yaml.documents) {
                    val services = (doc.topLevelValue as? YAMLMapping)?.getKeyValueByKey("services")?.value as? YAMLMapping ?: continue
                    for (kv in services.keyValues) {
                        val id = normalize(kv.keyText)
                        if (id.startsWith("_")) continue
                        byId.putIfAbsent(id, kv)
                        val value = kv.value
                        if (kv.keyText.trim('\'', '"').endsWith("\\") && value is YAMLMapping) {
                            val resource = value.getKeyValueByKey("resource")?.valueText
                            val dir = vf.parent
                            if (resource != null && dir != null) {
                                val base = dir.findFileByRelativePath(globBase(resource))
                                val excludes = (value.getKeyValueByKey("exclude")?.value).scalars()
                                    .mapNotNull { dir.findFileByRelativePath(globBase(it)) }
                                if (base != null) prefixes += ServicePrefix(id, base, excludes, kv, vf)
                            }
                            continue
                        }
                        val mapping = value as? YAMLMapping
                        val cls = mapping?.getKeyValueByKey("class")?.valueText?.let(::normalize)
                        val alias = mapping?.getKeyValueByKey("alias")?.valueText
                            ?: (value as? YAMLScalar)?.textValue?.takeIf { it.startsWith("@") }
                        val summary = mapping?.let(::summary).orEmpty()
                        when {
                            alias != null -> byClass.add(normalize(alias), ServiceUsage(kv, "$where: alias '$id'", vf))
                            cls != null -> byClass.add(cls, ServiceUsage(kv, "$where: service '$id'$summary", vf))
                            CLASS_NAME.matches(id) -> byClass.add(id, ServiceUsage(kv, "$where: service$summary", vf))
                        }
                        // Injektionen: '@Vendor\Foo' in arguments, calls, factory …
                        if (mapping != null) {
                            PsiTreeUtil.findChildrenOfType(mapping, YAMLScalar::class.java).forEach { s ->
                                val t = s.textValue
                                if (t.startsWith("@") && s.parent?.let { it is YAMLKeyValue && it.keyText == "alias" } != true) {
                                    val target = normalize(t)
                                    if (CLASS_NAME.matches(target)) byClass.add(target, ServiceUsage(s, "$where: injected into '$id'", vf))
                                }
                            }
                        }
                    }
                }
            }
            return ServicesIndex(byClass, byId, prefixes)
        }

        private fun MutableMap<String, MutableList<ServiceUsage>>.add(key: String, usage: ServiceUsage) {
            getOrPut(key) { ArrayList() } += usage
        }

        // ../Classes/{Foo,Bar}/x → ../Classes/
        fun globBase(path: String): String {
            val p = path.trim().trim('\'', '"')
            val cut = p.indexOfAny(charArrayOf('*', '{'))
            return (if (cut < 0) p else p.substring(0, cut)).trimEnd('/').ifEmpty { "." }
        }

        private fun YAMLValue?.scalars(): List<String> = when (this) {
            is YAMLScalar -> listOf(textValue)
            is YAMLSequence -> items.mapNotNull { (it.value as? YAMLScalar)?.textValue }
            else -> emptyList()
        }

        private fun summary(mapping: YAMLMapping): String {
            val parts = ArrayList<String>()
            FLAGS.forEach { flag -> mapping.getKeyValueByKey(flag)?.valueText?.let { parts += "$flag: $it" } }
            (mapping.getKeyValueByKey("tags")?.value as? YAMLSequence)?.items?.forEach { item ->
                when (val v = item.value) {
                    is YAMLScalar -> parts += "tag ${v.textValue}"
                    is YAMLMapping -> {
                        val name = v.getKeyValueByKey("name")?.valueText
                        val extra = v.keyValues.filter { it.keyText != "name" }.joinToString { "${it.keyText}: ${it.valueText}" }
                        parts += "tag $name" + if (extra.isNotEmpty()) " ($extra)" else ""
                    }
                    else -> {}
                }
            }
            listOf("arguments", "calls", "factory", "decorates").forEach { if (mapping.getKeyValueByKey(it) != null) parts += it }
            return if (parts.isEmpty()) "" else " - " + parts.joinToString(", ")
        }
    }
}
