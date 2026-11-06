package app.heckit.ide.t3toolkit.xliff

import app.heckit.ide.t3toolkit.settings.T3Settings
import com.intellij.openapi.project.Project

/**
 * Aufrufe, deren Argument ein kurzer Übersetzungs-Schlüssel ist - eingebaut und aus den Einstellungen.
 *
 * Eine Zeile je Aufruf, die Argumente in Klammern: `key` = Schlüssel, `extension` = Extension (Key oder UpperCamelCase),
 * `_` = überspringen; Alternativen mit `|` (`key|id`).
 * - ViewHelper: `my:translate(key|id, extensionName)` (Präfix `*` = jeder) - die Namen sind die Attribute.
 * - PHP: `MyUtility::translate(key, extension)`, `->translate(key)` oder `my_translate(key)` - Reihenfolge und benannte
 *   Argumente (`key:`) zählen.
 */
sealed class TranslationCall {
    /** Namen, unter denen der Schlüssel bzw. die Extension stehen (bei PHP: Position = Index in der Liste). */
    abstract val keyNames: Set<String>
    abstract val extensionNames: Set<String>
    abstract val word: String

    data class ViewHelper(val prefix: String, val name: String, override val keyNames: Set<String>, override val extensionNames: Set<String>) : TranslationCall() {
        override val word get() = name.substringAfterLast('.')
        private val p = if (prefix == "*") "[A-Za-z0-9_]+" else Regex.escape(prefix)
        val pattern = Regex("""(?:<$p:${Regex.escape(name)}\b[^>]*>?|\b$p:${Regex.escape(name)}\s*\([^)]*)""")
        val keyAttr = Regex("""\b(?:${keyNames.joinToString("|") { Regex.escape(it) }})\s*[=:]\s*(["'])([^"'{}]+)\1""")
        val extAttr = extensionNames.takeIf { it.isNotEmpty() }?.let {
            Regex("""\b(?:${it.joinToString("|") { n -> Regex.escape(n) }})\s*[=:]\s*(["'])([^"'{}]+)\1""")
        }
    }

    /** [arguments]: je Position die erlaubten Namen ("key", "extension" oder Parameternamen). */
    data class Php(val callee: String, val arguments: List<Set<String>>, override val keyNames: Set<String>, override val extensionNames: Set<String>) : TranslationCall() {
        override val word get() = callee.substringAfterLast(':').substringAfterLast('>')
        val pattern: Regex = when {
            "::" in callee -> Regex("""(?<![A-Za-z0-9_])${Regex.escape(callee)}\s*\(""")
            callee.startsWith("->") -> Regex("""(?:->|\?->)${Regex.escape(callee.removePrefix("->"))}\s*\(""")
            else -> Regex("""(?<![A-Za-z0-9_>:${'$'}\\])${Regex.escape(callee)}\s*\(""")
        }
        val keyIndex = arguments.indexOfFirst { it.any { n -> n in keyNames } }
        val extIndex = arguments.indexOfFirst { it.any { n -> n in extensionNames } }
    }

    companion object {
        const val DEFAULTS = "*:translate(key|id, extensionName)\nLocalizationUtility::translate(key, extensionName)"
        private val LINE = Regex("""^\s*([^\s(]+)\s*\(([^)]*)\)\s*$""")

        fun parse(text: String): List<TranslationCall> = text.lines().mapNotNull { raw ->
            val m = LINE.matchEntire(raw.substringBefore('#')) ?: return@mapNotNull null
            val callee = m.groupValues[1]
            val args = m.groupValues[2].split(',').map { a -> a.split('|').map { it.trim() }.filter { it.isNotEmpty() }.toSet() }
            // Erster Name einer Position mit "key" bzw. "extension" im Namen
            val keyNames = args.firstOrNull { s -> s.any { it.equals("key", true) || it.equals("id", true) } }
                ?: args.firstOrNull() ?: return@mapNotNull null
            val extNames = args.firstOrNull { s -> s.any { it.contains("extension", true) } }.orEmpty()
            val vh = Regex("""^([A-Za-z0-9_]+|\*):([A-Za-z0-9_.]+)$""").matchEntire(callee)
            when {
                vh != null -> TranslationCall.ViewHelper(vh.groupValues[1], vh.groupValues[2], keyNames, extNames)
                callee.matches(Regex("""^(?:->)?[A-Za-z_\\][A-Za-z0-9_\\]*(?:::[A-Za-z_][A-Za-z0-9_]*)?$""")) ->
                    Php(callee, args, keyNames, extNames)
                else -> null
            }
        }

        /** Eingebaute plus eingestellte Aufrufe. */
        fun all(project: Project): List<TranslationCall> {
            val custom = T3Settings.get(project).state.translationCalls
            return parse(DEFAULTS + "\n" + custom).distinct()
        }

        /**
         * Argumente eines PHP-Aufrufs ab [start] (direkt nach der öffnenden Klammer): je Argument Name (bei `name:`),
         * Text und Startoffset. Endet an der schließenden Klammer.
         */
        fun phpArguments(text: CharSequence, start: Int): List<Triple<String?, String, Int>> {
            val result = ArrayList<Triple<String?, String, Int>>()
            var depth = 0
            var quote: Char? = null
            var argStart = start
            var i = start
            fun add(end: Int) {
                val raw = text.subSequence(argStart, end).toString()
                val lead = raw.length - raw.trimStart().length
                val trimmed = raw.trim()
                val named = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*:(?!:)\s*""").find(trimmed)
                val value = if (named != null) trimmed.substring(named.value.length) else trimmed
                val offset = argStart + lead + (named?.value?.length ?: 0)
                if (trimmed.isNotEmpty()) result += Triple(named?.groupValues?.get(1), value, offset)
            }
            while (i < text.length && i - start < 2000) {
                val c = text[i]
                when {
                    quote != null -> if (c == '\\') i++ else if (c == quote) quote = null
                    c == '\'' || c == '"' -> quote = c
                    c == '(' || c == '[' || c == '{' -> depth++
                    c == ')' || c == ']' || c == '}' -> if (depth == 0) { add(i); return result } else depth--
                    c == ',' && depth == 0 -> { add(i); argStart = i + 1 }
                }
                i++
            }
            return result
        }

        private val STRING = Regex("""^(['"])([A-Za-z0-9_.\-:/]+)\1$""")

        /** Reiner String-Literal-Inhalt mit Offset, sonst null (Verkettungen, Variablen). */
        fun literal(arg: Triple<String?, String, Int>?): Pair<String, Int>? {
            val (_, value, offset) = arg ?: return null
            val m = STRING.matchEntire(value) ?: return null
            return m.groupValues[2] to offset + 1
        }
    }
}
