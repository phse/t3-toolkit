package app.heckit.ide.t3toolkit.create

/** Einträge in `Configuration/RequestMiddlewares.php` - Middlewares haben kein Attribut. */
object RequestMiddlewares {
    private const val INDENT = "        "

    fun entry(identifier: String, fqn: String, after: String, before: String): String = buildString {
        append("$INDENT'${esc(identifier)}' => [\n")
        append("$INDENT    'target' => \\${fqn.trimStart('\\')}::class,\n")
        list("after", after)?.let { append(it) }
        list("before", before)?.let { append(it) }
        append("$INDENT],\n")
    }

    private fun list(key: String, value: String): String? {
        val ids = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) return null
        return "$INDENT    '$key' => [\n" + ids.joinToString("") { "$INDENT        '${esc(it)}',\n" } + "$INDENT    ],\n"
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    /** Eintrag (4 Leerzeichen je Ebene) an die Einrückung der Datei anpassen, z. B. 2 Leerzeichen oder Tabs. */
    private fun reindent(entry: String, unit: String) = if (unit == "    ") entry else
        entry.lines().joinToString("\n") { line ->
            val spaces = line.length - line.trimStart(' ').length
            unit.repeat(spaces / 4) + line.trimStart(' ')
        }

    fun create(stack: String, entry: String) =
        "<?php\n\ndeclare(strict_types=1);\n\nreturn [\n    '$stack' => [\n$entry    ],\n];\n"

    /** Fügt den Eintrag am Anfang des Stacks ein bzw. legt den Stack an; null, wenn kein `return [` gefunden wird. */
    fun insert(text: String, stack: String, entry: String): String? {
        Regex("""(['"])${Regex.escape(stack)}\1\s*=>\s*(\[|array\s*\()[ \t]*\r?\n?""").find(text)?.let { m ->
            val at = m.range.last + 1
            val sep = if (m.value.endsWith("\n")) "" else "\n"
            val lineStart = text.lastIndexOf('\n', m.range.first) + 1
            val unit = text.substring(lineStart, m.range.first).takeIf { it.isNotEmpty() && it.isBlank() } ?: "    "
            return text.substring(0, at) + sep + reindent(entry, unit) + text.substring(at)
        }
        if (!Regex("""return\s*(\[|array\s*\()""").containsMatchIn(text)) return null
        val end = Regex("""(\]|\))\s*;\s*(\?>\s*)?$""").find(text.trimEnd()) ?: return null
        val head = text.substring(0, end.range.first).trimEnd()
        val comma = if (head.endsWith(",") || head.endsWith("[") || head.endsWith("(")) "" else ","
        return head + comma + "\n    '$stack' => [\n$entry    ],\n" + text.trimEnd().substring(end.range.first) + "\n"
    }
}
