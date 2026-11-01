package app.heckit.ide.t3toolkit

/** Minimaler TypoScript-Leser: Zuweisungen `a.b { c = x }` als (`a.b.c`, `x`). Bedingungen und Kopien werden ignoriert. */
object TypoScriptLite {
    val CONSTANT = Regex("""\{\$([A-Za-z0-9_.\-]+)}""")
    private val ASSIGN = Regex("""^([A-Za-z0-9_.\-]+)\s*=\s*(.*)$""")
    private val OPEN = Regex("""^([A-Za-z0-9_.\-]+)\s*\{\s*$""")

    fun assignments(text: String): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        val stack = ArrayList<String>()
        var inComment = false
        for (rawLine in text.lineSequence()) {
            var line = rawLine.trim()
            if (inComment) { if ("*/" in line) { inComment = false; line = line.substringAfter("*/").trim() } else continue }
            if (line.startsWith("/*")) { if ("*/" !in line) inComment = true; continue }
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
            if (line.startsWith("[")) { if (line.equals("[global]", true) || line.equals("[end]", true)) stack.clear(); continue }
            if (line == "}") { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1); continue }
            OPEN.matchEntire(line)?.let { stack += it.groupValues[1]; return@let }?.let { continue }
            ASSIGN.matchEntire(line)?.let { m ->
                result += (stack + m.groupValues[1]).joinToString(".") to m.groupValues[2].trim()
            }
        }
        return result
    }
}
