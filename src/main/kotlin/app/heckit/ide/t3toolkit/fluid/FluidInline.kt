package app.heckit.ide.t3toolkit.fluid

import com.intellij.lang.Language
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/**
 * Fluid-Inline-Syntax (`{item.title}`, `{f:format.date(date: x, format: 'd.m.Y')}`, `{x -> f:format.html()}`) in Texten:
 * Ausdrücke finden und in Teile zerlegen (für Farben, Vervollständigung und Prüfungen).
 */
object FluidInline {
    enum class Kind { BRACE, VIEWHELPER, VARIABLE, PROPERTY, ARGUMENT, STRING, NUMBER, KEYWORD, OPERATOR }

    data class Part(val kind: Kind, val start: Int, val end: Int)

    /** Ausdruck von `{` bis einschließlich `}` (absolute Offsets, [end] exklusiv). */
    data class Expression(val start: Int, val end: Int)

    private val KEYWORDS = setOf("true", "false", "null", "and", "or", "not", "namespace")
    private val STRICT_ACCESSOR = Regex("""[A-Za-z_][A-Za-z0-9_.]*""")
    private val VIEWHELPER_CALL = Regex("""[A-Za-z0-9_]+:[A-Za-z0-9_.]+\s*\(""")
    private const val MAX_LENGTH = 5000

    /**
     * Ausdrücke in [text] zwischen [from] und [to]. [strict]: nur eindeutige Fluid-Ausdrücke (in Skripten und Styles,
     * wo geschweifte Klammern sonst JavaScript bzw. CSS sind).
     */
    fun expressions(text: CharSequence, from: Int = 0, to: Int = text.length, strict: Boolean = false): List<Expression> {
        val result = ArrayList<Expression>()
        var i = from
        while (i < to) {
            if (text[i] != '{' || !isStart(text, i, to)) { i++; continue }
            val end = matchingEnd(text, i, to)
            if (end < 0) { i++; continue }
            if (!strict || isClearlyFluid(text.subSequence(i + 1, end - 1))) {
                result += Expression(i, end)
                i = end
            } else i++
        }
        return result
    }

    private fun isStart(text: CharSequence, i: Int, to: Int): Boolean {
        if (i + 1 >= to) return false
        val next = text[i + 1]
        // {{ … }} (Vue, Angular) und ${…} (JavaScript) sind kein Fluid
        if (next == '{' || (i > 0 && (text[i - 1] == '{' || text[i - 1] == '$'))) return false
        return next.isLetter() || next == '_' || next == '\'' || next == '"'
    }

    private fun isClearlyFluid(content: CharSequence): Boolean {
        if (';' in content || '\n' in content) return false
        return STRICT_ACCESSOR.matches(content) || VIEWHELPER_CALL.containsMatchIn(content) || "->" in content
    }

    /** Offset hinter der passenden `}` oder -1. */
    fun matchingEnd(text: CharSequence, start: Int, to: Int = text.length): Int {
        var depth = 0
        var j = start
        val limit = minOf(to, start + MAX_LENGTH)
        while (j < limit) {
            when (val c = text[j]) {
                '\'', '"' -> {
                    j++
                    while (j < limit && text[j] != c) { if (text[j] == '\\') j++; j++ }
                }
                // \'…\' - String in einem Ausdruck, der selbst in einem String steht
                '\\' -> if (j + 1 < limit && (text[j + 1] == '\'' || text[j + 1] == '"')) j = escapedStringEnd(text, j, limit) - 1
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return j + 1 }
                '<' -> if (j + 1 < limit && (text[j + 1].isLetter() || text[j + 1] == '/')) return -1 // Tag mitten im Ausdruck: kein Fluid
            }
            j++
        }
        return -1
    }

    /** Ende (exklusiv) eines Strings `\'…\'` ab dem Backslash bei [start]. */
    private fun escapedStringEnd(text: CharSequence, start: Int, limit: Int): Int {
        val q = text[start + 1]
        var j = start + 2
        while (j + 1 < limit && !(text[j] == '\\' && text[j + 1] == q)) j++
        return minOf(j + 2, limit)
    }

    /** Teile eines Ausdrucks mit absoluten Offsets. */
    fun parts(text: CharSequence, e: Expression): List<Part> {
        val result = ArrayList<Part>()
        val end = e.end - 1
        result += Part(Kind.BRACE, e.start, e.start + 1)
        var i = e.start + 1
        var previous = '{'
        while (i < end) {
            val c = text[i]
            when {
                c.isWhitespace() -> { i++; continue }
                c == '\\' && i + 1 < end && (text[i + 1] == '\'' || text[i + 1] == '"') -> {
                    val j = escapedStringEnd(text, i, end)
                    result += Part(Kind.STRING, i, j)
                    i = j
                }
                c == '\'' || c == '"' -> {
                    var j = i + 1
                    while (j < end && text[j] != c) { if (text[j] == '\\') j++; j++ }
                    result += Part(Kind.STRING, i, minOf(j + 1, end))
                    i = j + 1
                }
                c.isDigit() -> {
                    var j = i
                    while (j < end && (text[j].isDigit() || text[j] == '.')) j++
                    result += Part(if (previous == '.') Kind.PROPERTY else Kind.NUMBER, i, j)
                    i = j
                }
                c.isLetter() || c == '_' -> {
                    var j = i
                    while (j < end && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                    val word = text.subSequence(i, j).toString()
                    // ns:viewHelper.name(
                    if (j + 1 < end && text[j] == ':' && (text[j + 1].isLetter())) {
                        var k = j + 1
                        while (k < end && (text[k].isLetterOrDigit() || text[k] == '_' || text[k] == '.')) k++
                        var m = k
                        while (m < end && text[m].isWhitespace()) m++
                        if (m < end && text[m] == '(') {
                            result += Part(Kind.VIEWHELPER, i, k)
                            i = k
                            previous = 'x'
                            continue
                        }
                    }
                    var m = j
                    while (m < end && text[m].isWhitespace()) m++
                    val kind = when {
                        previous == '.' -> Kind.PROPERTY
                        m < end && text[m] == ':' -> Kind.ARGUMENT
                        word in KEYWORDS -> Kind.KEYWORD
                        else -> Kind.VARIABLE
                    }
                    result += Part(kind, i, j)
                    i = j
                    previous = 'x'
                    continue
                }
                c == '{' || c == '}' -> { result += Part(Kind.BRACE, i, i + 1); i++ }
                c == '-' && i + 1 < end && text[i + 1] == '>' -> { result += Part(Kind.OPERATOR, i, i + 2); i += 2 }
                c in "=!<>&|?%+*/" -> {
                    val two = i + 1 < end && text[i + 1] in "=&|"
                    result += Part(Kind.OPERATOR, i, if (two) i + 2 else i + 1)
                    i += if (two) 2 else 1
                }
                else -> i++
            }
            previous = c
        }
        result += Part(Kind.BRACE, end, end + 1)
        return result
    }

    /** `{` des innersten offenen Ausdrucks vor [offset], gezählt ab [from] (Anfang des Texts bzw. Attributwerts). */
    fun openAt(text: CharSequence, from: Int, offset: Int): Int? {
        val stack = ArrayList<Int>()
        var quote: Char? = null
        var i = maxOf(from, offset - MAX_LENGTH)
        while (i < offset) {
            val c = text[i]
            if (quote != null) {
                if (c == '\\') i++ else if (c == quote) quote = null
            } else when (c) {
                '\'', '"' -> if (stack.isNotEmpty()) quote = c
                '{' -> stack += i
                '}' -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            }
            i++
        }
        // Der äußerste offene Ausdruck muss ein Fluid-Anfang sein
        val outer = stack.firstOrNull() ?: return null
        if (outer + 1 < offset && !isStart(text, outer, offset)) return null
        return stack.last()
    }

    /** Ob [file] ein Fluid-Template ist (unter Resources/Private oder mit Fluid-Namespace bzw. -Tags). */
    fun isFluidFile(file: PsiFile): Boolean = CachedValuesManager.getCachedValue(file) {
        val vf: VirtualFile? = file.virtualFile ?: file.originalFile.virtualFile
        val text = file.viewProvider.contents
        val fluid = vf != null && vf.extension?.lowercase() in setOf("html", "htm", "xml", "txt") &&
            ("/Resources/Private/" in vf.path || "xmlns:f=" in text || "{namespace " in text || "<f:" in text)
        CachedValueProvider.Result.create(fluid, file)
    }

    /** Das Fluid-Plugin von sgalinski bringt eigene Farben und Vervollständigung mit - dann nicht doppelt. */
    fun otherFluidPluginActive(): Boolean = Language.findLanguageByID("FLUID") != null
}
