package app.heckit.ide.t3toolkit.typoscript

import app.heckit.ide.t3toolkit.T3Project
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
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
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.PhpClass
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

/**
 * Site-Settings (TYPO3 v13+), die TypoScript als `{$name}` liest: Definitionen aus `Configuration/Sets/…/settings.definitions.yaml`
 * und Werte aus `settings.yaml` der Sets und Sites (`config/sites/…`).
 */
object TsSiteSettings {
    data class Setting(val name: String, val element: PsiElement, val label: String?, val default: String?, val isDefinition: Boolean)

    fun all(project: Project): Map<String, List<Setting>> = CachedValuesManager.getManager(project).getCachedValue(project) {
        CachedValueProvider.Result.create(build(project), PsiModificationTracker.MODIFICATION_COUNT)
    }

    private fun build(project: Project): Map<String, List<Setting>> {
        val result = LinkedHashMap<String, MutableList<Setting>>()
        val scope = GlobalSearchScope.allScope(project)
        val psi = PsiManager.getInstance(project)
        FilenameIndex.getVirtualFilesByName("settings.definitions.yaml", scope).forEach { vf ->
            val file = psi.findFile(vf) as? YAMLFile ?: return@forEach
            val settings = (file.documents.firstOrNull()?.topLevelValue as? YAMLMapping)?.getKeyValueByKey("settings")?.value as? YAMLMapping
                ?: return@forEach
            settings.keyValues.forEach { kv ->
                val def = kv.value as? YAMLMapping
                result.getOrPut(kv.keyText) { ArrayList() } +=
                    Setting(kv.keyText, kv, def?.getKeyValueByKey("label")?.valueText, def?.getKeyValueByKey("default")?.valueText, true)
            }
        }
        FilenameIndex.getVirtualFilesByName("settings.yaml", scope).forEach { vf ->
            if ("/Sets/" !in vf.path && "/sites/" !in vf.path) return@forEach
            val file = psi.findFile(vf) as? YAMLFile ?: return@forEach
            val top = file.documents.firstOrNull()?.topLevelValue as? YAMLMapping ?: return@forEach
            flatten(top, "") { name, kv -> result.getOrPut(name) { ArrayList() } += Setting(name, kv, null, kv.valueText, false) }
        }
        return result
    }

    private fun flatten(mapping: YAMLMapping, prefix: String, add: (String, YAMLKeyValue) -> Unit) {
        mapping.keyValues.forEach { kv ->
            val name = prefix + kv.keyText
            val value = kv.value
            if (value is YAMLMapping) flatten(value, "$name.", add) else add(name, kv)
        }
    }
}

/** `EXT:`-Pfade vervollständigen: erst Extension-Schlüssel, dann Ordner und Dateien. */
object ExtPathCompletion {
    private val EXT_TYPED = Regex("""EXT:([A-Za-z0-9_]*)(/[^\s"'<>(){},]*)?$""")

    /** Fügt Vorschläge hinzu, wenn [before] (Text bis zum Cursor) mit einem `EXT:`-Pfad endet; true, wenn ja. */
    fun complete(project: Project, before: String, result: CompletionResultSet, extensions: Set<String>? = null): Boolean {
        val m = EXT_TYPED.find(before) ?: return false
        val t3 = T3Project.get(project)
        if (m.groups[2] == null) {
            val matcher = result.withPrefixMatcher(m.value)
            t3.extensions.forEach { (key, dir) ->
                matcher.addElement(
                    LookupElementBuilder.create("EXT:$key/").withPresentableText("EXT:$key").withIcon(AllIcons.Nodes.Module)
                        .withTypeText(dir.name).withInsertHandler { ctx, _ -> AutoPopupController.getInstance(ctx.project).scheduleAutoPopup(ctx.editor) },
                )
            }
            matcher.stopHere()
            return true
        }
        val rest = m.groupValues[2].removePrefix("/")
        val dirPath = rest.substringBeforeLast('/', "")
        val root = t3.extensions[m.groupValues[1]] ?: return true
        val dir = if (dirPath.isEmpty()) root else root.findFileByRelativePath(dirPath) ?: return true
        val matcher = result.withPrefixMatcher(rest.substringAfterLast('/'))
        dir.children.sortedWith(compareBy({ !it.isDirectory }, { it.name })).forEach { child ->
            if (child.name.startsWith(".")) return@forEach
            if (!child.isDirectory && extensions != null && child.extension?.lowercase() !in extensions) return@forEach
            matcher.addElement(
                if (child.isDirectory) LookupElementBuilder.create(child.name + "/").withPresentableText(child.name).withIcon(AllIcons.Nodes.Folder)
                    .withInsertHandler { ctx, _ -> AutoPopupController.getInstance(ctx.project).scheduleAutoPopup(ctx.editor) }
                else LookupElementBuilder.create(child.name).withIcon(child.fileType.icon),
            )
        }
        matcher.stopHere()
        return true
    }
}

/** PHP-Klassen in TypoScript-Werten (`userFunc = Vendor\Ext\Foo->bar`, `dataProcessing.10 = Vendor\…\Processor`). */
object TsPhp {
    val CLASS_REF = Regex("""\\?([A-Za-z_][A-Za-z0-9_]*(?:\\[A-Za-z_][A-Za-z0-9_]*)+)(?:(->|::)([A-Za-z_][A-Za-z0-9_]*))?""")

    fun classes(project: Project, fqn: String): Collection<PhpClass> = PhpIndex.getInstance(project).getAnyByFQN("\\" + fqn.trimStart('\\'))

    /** Klasse bzw. Methode an [offsetInValue] in [value]. */
    fun targetsAt(project: Project, value: String, offsetInValue: Int): List<PsiElement> {
        val v = value.trim()
        TsTypes.processorAlias(v)?.takeIf { v == it }?.let { alias ->
            return classes(project, TsRef.dataProcessors[alias]!!).toList()
        }
        val lead = value.indexOf(v)
        CLASS_REF.findAll(v).forEach { m ->
            if (offsetInValue - lead !in m.range.first..m.range.last + 1) return@forEach
            val cls = classes(project, m.groupValues[1])
            val method = m.groups[3]
            if (method != null && offsetInValue - lead >= method.range.first) {
                return cls.mapNotNull { it.findMethodByName(method.value) }
            }
            return cls.toList()
        }
        return emptyList()
    }
}

/** Auflösung von `@import`-Pfaden (EXT:, relativ, mit `*`-Muster) und `<INCLUDE_TYPOSCRIPT>`-Quellen. */
object TsImports {
    /** Dateien (oder der Ordner bei Mustern ohne Treffer) zu [path], relativ zu [context]. */
    fun resolve(project: Project, path: String, context: VirtualFile?): List<VirtualFile> {
        val p = path.trim()
        val base: VirtualFile?
        val rel: String
        if (p.startsWith("EXT:")) {
            val key = p.removePrefix("EXT:").substringBefore('/')
            base = T3Project.get(project).extensions[key]
            rel = p.removePrefix("EXT:$key").trimStart('/')
        } else {
            base = context?.parent
            rel = p.removePrefix("./")
        }
        if (base == null) return emptyList()
        if ('*' !in rel) return listOfNotNull(if (rel.isEmpty()) base else base.findFileByRelativePath(rel))
        // Muster: Ordner bis zum ersten *, Dateien darin passend zum Muster
        val dirPart = rel.substringBefore('*').substringBeforeLast('/', "")
        val dir = if (dirPart.isEmpty()) base else base.findFileByRelativePath(dirPart) ?: return emptyList()
        val pattern = Regex(rel.removePrefix(dirPart).trimStart('/').split('*').joinToString("[^/]*") { Regex.escape(it) })
        val matches = ArrayList<VirtualFile>()
        VfsUtilCore.iterateChildrenRecursively(dir, null) { f ->
            if (!f.isDirectory) VfsUtilCore.getRelativePath(f, dir)?.let { if (pattern.matches(it)) matches += f }
            true
        }
        return matches.sortedBy { it.path }.ifEmpty { listOf(dir) }
    }

    /** Ob [path] auflösbar ist (Muster: Ordner vorhanden). */
    fun exists(project: Project, path: String, context: VirtualFile?): Boolean = resolve(project, path, context).isNotEmpty()
}
