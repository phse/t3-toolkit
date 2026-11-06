package app.heckit.ide.t3toolkit.xliff

import app.heckit.ide.t3toolkit.T3Project
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/** Eine Übersetzungs-Angabe im Text: Datei (`EXT:…xlf`) und Schlüssel, mit ihren Bereichen. */
data class LabelRef(val file: VirtualFile?, val filePath: String, val fileRange: IntRange?, val key: String, val keyRange: IntRange)

/** Ein `<trans-unit>` einer XLF-Datei. */
data class TransUnit(val id: String, val offset: Int, val source: String?, val target: String?)

/**
 * Übersetzungen: `LLL:EXT:my_ext/Resources/Private/Language/locallang.xlf:key` in jeder Datei und kurze Schlüssel
 * in `<f:translate key="…"/>` (dann `locallang.xlf` der Extension aus `extensionName` bzw. der Datei).
 */
object Xliff {
    /** Gruppe 1: Pfad ab `EXT:`, Gruppe 2: Schlüssel (kann leer sein). */
    val LLL = Regex("""LLL:(EXT:[A-Za-z0-9_]+/[^\s"'<>(){},:]+?\.xlf)(?::([A-Za-z0-9_.\-]*))?""")
    private val TRANS_UNIT = Regex("""<trans-unit\b[^>]*?\bid\s*=\s*(["'])([^"']+)\1[^>]*>(.*?)</trans-unit>""", RegexOption.DOT_MATCHES_ALL)
    private val SOURCE = Regex("""<source\b[^>]*>(.*?)</source>""", RegexOption.DOT_MATCHES_ALL)
    private val TARGET = Regex("""<target\b[^>]*>(.*?)</target>""", RegexOption.DOT_MATCHES_ALL)
    /** `de.locallang.xlf`, `de_AT.locallang.xlf` */
    private val LANG_PREFIX = Regex("""^[a-z]{2,3}(?:[_-][A-Za-z]{2,4})?\.""")
    const val DEFAULT_FILE = "Resources/Private/Language/locallang.xlf"

    fun isTranslation(file: VirtualFile) = LANG_PREFIX.containsMatchIn(file.name)

    /** Die Ausgangsdatei zu einer Übersetzung (`de.locallang.xlf` → `locallang.xlf`). */
    fun defaultOf(file: VirtualFile): VirtualFile =
        if (isTranslation(file)) file.parent?.findChild(file.name.replaceFirst(LANG_PREFIX, "")) ?: file else file

    /** Übersetzungen neben [file] (`de.locallang.xlf` …), nach Sprache sortiert. */
    fun translationsOf(file: VirtualFile): List<VirtualFile> =
        file.parent?.children.orEmpty().filter { it.name != file.name && it.name.endsWith("." + file.name) && isTranslation(it) }.sortedBy { it.name }

    fun units(project: Project, file: VirtualFile): Map<String, TransUnit> {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return emptyMap()
        return CachedValuesManager.getCachedValue(psi) {
            CachedValueProvider.Result.create(parse(psi.viewProvider.contents), psi)
        }
    }

    private fun parse(text: CharSequence): Map<String, TransUnit> {
        val result = LinkedHashMap<String, TransUnit>()
        TRANS_UNIT.findAll(text).forEach { m ->
            val body = m.groupValues[3]
            fun clean(s: String?) = s?.replace(Regex("""<!\[CDATA\[|]]>"""), "")?.replace(Regex("""\s+"""), " ")?.trim()
            result[m.groupValues[2]] = TransUnit(
                m.groupValues[2], m.groups[2]!!.range.first,
                clean(SOURCE.find(body)?.groupValues?.get(1)), clean(TARGET.find(body)?.groupValues?.get(1)),
            )
        }
        return result
    }

    /** Alle Angaben in [text] ([file] = die Datei, für kurze Schlüssel in Fluid). */
    fun refs(project: Project, file: VirtualFile?, text: CharSequence): List<LabelRef> {
        val calls = TranslationCall.all(project)
        if ("LLL:" !in text && calls.none { it.word in text }) return emptyList()
        val t3 = T3Project.get(project)
        val result = ArrayList<LabelRef>()
        LLL.findAll(text).forEach { m ->
            val path = m.groups[1]!!
            result += LabelRef(t3.resolveExtPath(path.value), path.value, path.range, m.groups[2]?.value.orEmpty(),
                m.groups[2]?.range ?: (m.range.last + 1..m.range.last))
        }
        fun short(key: String, keyStart: Int, extensionName: String?) {
            if (key.startsWith("LLL:") || file == null) return
            var path = defaultPath(t3, file, extensionName)
            // Ohne extensionName: Steht der Schlüssel nicht in der eigenen locallang.xlf, die der anderen Extensions
            // durchsuchen (Translate-ViewHelper von Projekten probieren meist alle geladenen Extensions durch)
            if (extensionName == null && path?.let { p -> t3.resolveExtPath(p)?.let { key in units(project, it) } } != true) {
                t3.extensions.keys.firstOrNull { k ->
                    t3.resolveExtPath("EXT:$k/$DEFAULT_FILE")?.let { key in units(project, it) } == true
                }?.let { path = "EXT:$it/$DEFAULT_FILE" }
            }
            val p = path ?: return
            result += LabelRef(t3.resolveExtPath(p), p, null, key, keyStart until keyStart + key.length)
        }
        if (file == null) return result
        val ext = file.extension?.lowercase()
        for (call in calls) {
            if (call.word !in text) continue
            when (call) {
                is TranslationCall.ViewHelper -> if (ext == "html") call.pattern.findAll(text).forEach { t ->
                    val key = call.keyAttr.find(t.value) ?: return@forEach
                    short(key.groupValues[2], t.range.first + key.groups[2]!!.range.first, call.extAttr?.find(t.value)?.groupValues?.get(2))
                }
                is TranslationCall.Php -> if (ext == "php") call.pattern.findAll(text).forEach { m ->
                    val args = TranslationCall.phpArguments(text, m.range.last + 1)
                    fun arg(index: Int, names: Set<String>) =
                        args.firstOrNull { it.first != null && it.first in names }
                            ?: args.getOrNull(index)?.takeIf { it.first == null }
                    val key = TranslationCall.literal(arg(call.keyIndex, call.arguments.getOrNull(call.keyIndex).orEmpty())) ?: return@forEach
                    val extension = if (call.extIndex < 0) null
                        else TranslationCall.literal(arg(call.extIndex, call.arguments[call.extIndex]))?.first
                    short(key.first, key.second, extension)
                }
            }
        }
        return result
    }

    /** `EXT:<ext>/Resources/Private/Language/locallang.xlf` für kurze Schlüssel. */
    private fun defaultPath(t3: T3Project, file: VirtualFile, extensionName: String?): String? {
        val key = if (extensionName != null) {
            val wanted = extensionName.replace("_", "").lowercase()
            t3.extensions.keys.firstOrNull { it.replace("_", "").lowercase() == wanted } ?: return null
        } else t3.extensionOf(file)?.first ?: return null
        return "EXT:$key/$DEFAULT_FILE"
    }

    /** Angabe an [offset] (im Dateipfad oder Schlüssel). */
    fun refAt(project: Project, file: VirtualFile?, text: CharSequence, offset: Int): LabelRef? {
        val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        // f:translate kann über mehrere Zeilen gehen - etwas Vorlauf lesen
        val start = maxOf(0, minOf(lineStart, offset - 500))
        return refs(project, file, text.subSequence(start, lineEnd)).map { it.shift(start) }.firstOrNull { r ->
            offset in r.keyRange.first..r.keyRange.last + 1 || r.fileRange?.let { offset in it.first..it.last + 1 } == true
        }
    }

    private fun LabelRef.shift(by: Int) = copy(
        fileRange = fileRange?.let { it.first + by..it.last + by },
        keyRange = keyRange.first + by..keyRange.last + by,
    )

    /** Alle Ausgangs-XLF-Dateien der Extensions als `EXT:`-Pfad. */
    fun languageFiles(project: Project): List<Pair<String, VirtualFile>> {
        val t3 = T3Project.get(project)
        return FilenameIndex.getAllFilesByExt(project, "xlf", GlobalSearchScope.allScope(project))
            .filter { !isTranslation(it) }
            .mapNotNull { f -> t3.extPathOf(f)?.let { it to f } }
            .distinctBy { it.first }
            .sortedBy { it.first }
    }

    /** Wo wird welcher Schlüssel der Ausgangsdatei [file] verwendet? */
    fun usages(project: Project, file: VirtualFile): Map<String, List<PsiElement>> {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return emptyMap()
        return CachedValuesManager.getCachedValue(psi) {
            CachedValueProvider.Result.create(collectUsages(project, file), PsiModificationTracker.MODIFICATION_COUNT)
        }
    }

    private fun collectUsages(project: Project, file: VirtualFile): Map<String, List<PsiElement>> {
        val psiManager = PsiManager.getInstance(project)
        val candidates = LinkedHashSet<PsiFile>()
        val helper = PsiSearchHelper.getInstance(project)
        val scope = GlobalSearchScope.allScope(project)
        val words = buildList {
            add(file.nameWithoutExtension)
            if (file.name == "locallang.xlf") TranslationCall.all(project).forEach { add(it.word) }
        }
        words.forEach { word ->
            helper.processCandidateFilesForText(scope, UsageSearchContext.ANY, true, word) { vf ->
                psiManager.findFile(vf)?.let { candidates += it }; true
            }
        }
        val result = LinkedHashMap<String, MutableList<PsiElement>>()
        for (candidate in candidates) {
            val vf = candidate.virtualFile ?: continue
            if (vf.extension?.lowercase() == "xlf") continue
            refs(project, vf, candidate.viewProvider.contents).forEach { r ->
                if (r.file != file || r.key.isEmpty()) return@forEach
                candidate.findElementAt(r.keyRange.first)?.let { result.getOrPut(r.key) { ArrayList() } += it }
            }
        }
        return result
    }

}
