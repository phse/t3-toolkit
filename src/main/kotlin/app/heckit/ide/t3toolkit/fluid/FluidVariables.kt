package app.heckit.ide.t3toolkit.fluid

import app.heckit.ide.t3toolkit.T3Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext

/**
 * Sprung von einer Fluid-Variablen (`{title}`, `{item.name}`, `each="{components}"`) zu ihrer Herkunft:
 * Definition in derselben Datei (f:for as/iteration, f:variable, f:alias), `<fc:param>` einer Component,
 * die `f:render`-Aufrufe, die sie einem Partial übergeben, und für Templates TypoScript (`as =`, `variables.`)
 * sowie PHP (`assign('name'`, `assignMultiple`, `$processedData['name']`).
 */
object FluidVariables {
    private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val RESERVED = setOf("true", "false", "null", "and", "or", "_all", "settings")

    /** Variablenname an der Spalte [col] von [line], wenn dort eine Variable steht (nicht Argumentname, ViewHelper, Eigenschaft). */
    fun nameAt(line: CharSequence, col: Int): String? {
        val m = IDENT.findAll(line).firstOrNull { col in it.range.first..it.range.last + 1 } ?: return null
        val start = m.range.first
        val end = m.range.last + 1
        // innerhalb von { … }, aber nicht in einem '…'-String
        var depth = 0
        var quote = false
        for (i in 0 until start) when (line[i]) {
            '\'' -> quote = !quote
            '{' -> if (!quote) depth++
            '}' -> if (!quote && depth > 0) depth--
        }
        if (depth == 0 || quote) return null
        val before = line.subSequence(0, start).trimEnd().lastOrNull()
        val after = line.subSequence(end, line.length).trimStart().firstOrNull()
        if (before == '.' || after == ':' || after == '(') return null
        // ns:viewHelper.name - der Teil nach dem Präfix ist keine Variable
        if (start >= 2 && line[start - 1] == ':' && (line[start - 2].isLetterOrDigit() || line[start - 2] == '_')) return null
        if (before != null && before !in "{(,:!=<>[" && !line.subSequence(0, start).trimEnd().endsWith("->")) {
            // z. B. "a > b", "x % 2", "not x" - Operatoren mit Leerzeichen zulassen
            if (!line.subSequence(0, start).endsWith(" ")) return null
        }
        return m.value.takeIf { it !in RESERVED }
    }

    fun targets(file: PsiFile, offset: Int, name: String): List<PsiElement> {
        val project = file.project
        val vf = file.virtualFile ?: return emptyList()
        val text = file.viewProvider.contents

        // 1. Definition in derselben Datei, die nächste davor
        val n = Regex.escape(name)
        val local = listOf(
            Regex("""\b(?:as|iteration|key|groupKey)\s*=\s*["']$n["']"""),
            Regex("""f:variable\b[^>}]*?\bname\s*[=:]\s*["']$n["']"""),
            Regex("""f:alias\b[^>]*?\bmap\s*=\s*["']\{[^}]*?\b$n\s*:"""),
            Regex("""f:(?:for|groupedFor)\([^)]*?\b(?:as|iteration|key)\s*:\s*'$n'"""),
        ).flatMap { it.findAll(text).toList() }.filter { it.range.first < offset }.maxByOrNull { it.range.first }
        local?.let { m -> file.findElementAt(m.range.first + m.value.lastIndexOf(name))?.let { return listOf(it) } }

        // 2. Parameter einer Component
        if (Fluid.isComponent(vf, text)) {
            return Fluid.componentParams(project, vf).filter { it.name == name }.map { it.tag }
        }

        val result = ArrayList<PsiElement>()
        // 3. Partial: f:render-Aufrufe, die die Variable übergeben (oder _all)
        val role = Fluid.roleOf(project, vf, text)
        if (role?.kind == "Partials") {
            val argKey = Regex("""arguments\s*[=:]\s*["']?\{[^"]*?\b($n)\s*:""")
            for (usage in Fluid.usages(project, vf, role)) {
                val uText = usage.containingFile.viewProvider.contents
                val at = usage.textRange.startOffset
                // ganzer Aufruf: vom Tag- bzw. Inline-Anfang bis zum Ende (arguments kann vor partial stehen)
                val start = maxOf(uText.lastIndexOf('<', at), uText.lastIndexOf("{f:render", at), 0)
                val end = listOf(uText.indexOf("/>", at), uText.indexOf(">", at), uText.indexOf(")}", at))
                    .filter { it >= 0 }.minOrNull()?.plus(2) ?: uText.length
                val call = uText.subSequence(start, end.coerceAtMost(uText.length)).toString()
                val hit = argKey.find(call)
                when {
                    hit != null -> usage.containingFile.findElementAt(start + hit.groups[1]!!.range.first)?.let(result::add)
                    "_all" in call -> result += usage
                }
            }
        }
        // 4. Templates (und Partials mit _all): TypoScript und PHP
        if (role?.kind != "Partials" || result.isEmpty()) {
            val psiManager = PsiManager.getInstance(project)
            val ts = listOf(Regex("""(?m)^\s*(?:[A-Za-z0-9_.]+\.)?as\s*=\s*$n\s*$"""), Regex("""(?m)\bvariables\s*\.\s*$n\s*[=<{]"""),
                Regex("""(?m)^\s*$n\s*(?:=\s*[A-Z]|<|\{)"""))
            for (tsFile in T3Project.get(project).typoScriptFiles) {
                val t = runCatching { VfsUtilCore.loadText(tsFile) }.getOrNull() ?: continue
                if (name !in t) continue
                val psi = psiManager.findFile(tsFile) ?: continue
                ts.take(2).forEach { r -> r.findAll(t).forEach { m -> psi.findElementAt(m.range.first + m.value.indexOf(name))?.let(result::add) } }
                // `name = TEXT` usw. nur innerhalb eines variables-Blocks
                if (Regex("""variables\s*\{""").containsMatchIn(t)) ts[2].findAll(t).forEach { m ->
                    val blockStart = t.lastIndexOf("variables", m.range.first)
                    if (blockStart >= 0 && t.indexOf('}', blockStart) > m.range.first) psi.findElementAt(m.range.first + m.value.indexOf(name))?.let(result::add)
                }
            }
            val php = listOf(
                Regex("""->assign\(\s*['"]$n['"]"""),
                Regex("""\${'$'}processedData\[\s*['"]$n['"]\s*]\s*="""),
                Regex("""['"]$n['"]\s*=>"""),
            )
            val candidates = LinkedHashSet<PsiFile>()
            PsiSearchHelper.getInstance(project).processCandidateFilesForText(
                GlobalSearchScope.projectScope(project), UsageSearchContext.IN_STRINGS, true, name,
            ) { f -> if (f.extension == "php") psiManager.findFile(f)?.let { candidates += it }; true }
            for (c in candidates) {
                val t = c.viewProvider.contents
                php.forEachIndexed { i, r ->
                    r.findAll(t).forEach { m ->
                        // 'name' => nur in assignMultiple([...]) zählen
                        if (i == 2 && t.lastIndexOf("assignMultiple", m.range.first).let { it < 0 || m.range.first - it > 2000 }) return@forEach
                        c.findElementAt(m.range.first + m.value.indexOf(name))?.let(result::add)
                    }
                }
            }
        }
        return result
    }
}
