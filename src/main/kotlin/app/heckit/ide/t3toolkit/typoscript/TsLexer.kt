package app.heckit.ide.t3toolkit.typoscript

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * TypoScript-Lexer nach dem Vorbild des TYPO3-Tokenizers (`LosslessTokenizer`, v12+): zeilenweise, links ein Pfad bis zum
 * ersten Leerzeichen bzw. Operator, rechts der Wert bis zum Zeilenende. Was nach `{`, `}`, `>`, einer Bedingung oder einer
 * Kopierquelle noch in der Zeile steht, ist für TYPO3 ein Kommentar.
 *
 * Der Lexer zerlegt bei [start] den ganzen Bereich auf einmal. Zustand 0 tragen nur die ersten Tokens einer Zeile außerhalb
 * mehrzeiliger Konstrukte - dort kann die IDE neu aufsetzen.
 */
class TsLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var end = 0
    private var types = ArrayList<IElementType>()
    private var starts = IntArray(64)
    private var states = IntArray(64)
    private var count = 0
    private var index = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.end = endOffset
        types = ArrayList()
        count = 0
        index = 0
        Scanner(buffer, startOffset, endOffset).run()
    }

    override fun getState() = if (index < count) states[index] else 0
    override fun getTokenType(): IElementType? = if (index < count) types[index] else null
    override fun getTokenStart() = if (index < count) starts[index] else end
    override fun getTokenEnd() = if (index + 1 < count) starts[index + 1] else end
    override fun advance() { if (index < count) index++ }
    override fun getBufferSequence() = buffer
    override fun getBufferEnd() = end

    private fun add(type: IElementType, start: Int, state: Int) {
        // Leerraum zusammenfassen (Zeilenende + Einrückung)
        if (count > 0 && type == TokenType.WHITE_SPACE && types[count - 1] == TokenType.WHITE_SPACE) return
        if (count == starts.size) {
            starts = starts.copyOf(count * 2)
            states = states.copyOf(count * 2)
        }
        types += type
        starts[count] = start
        states[count] = state
        count++
    }

    private inner class Scanner(val s: CharSequence, start: Int, val limit: Int) {
        var pos = start
        /** Zustand des nächsten Tokens: 0 am Zeilenanfang. */
        var state = 0

        fun emit(type: IElementType, to: Int) {
            if (to <= pos) return
            add(type, pos, state)
            pos = to
            if (type != TokenType.WHITE_SPACE) state = 1
        }

        fun lineEnd(from: Int = pos): Int {
            var i = from
            while (i < limit && s[i] != '\n') i++
            // \r gehört zum Zeilenende
            return if (i > from && s[i - 1] == '\r') i - 1 else i
        }

        fun blanks() {
            var i = pos
            while (i < limit && (s[i] == ' ' || s[i] == '\t')) i++
            emit(TokenType.WHITE_SPACE, i)
        }

        fun startsWith(prefix: String, at: Int = pos): Boolean {
            if (at + prefix.length > limit) return false
            for (k in prefix.indices) if (s[at + k] != prefix[k]) return false
            return true
        }

        fun run() {
            while (pos < limit) {
                // Zeilenumbruch und Einrückung
                var i = pos
                while (i < limit && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
                if (i > pos) {
                    add(TokenType.WHITE_SPACE, pos, state)
                    pos = i
                }
                if (pos >= limit) break
                state = 0
                line()
            }
        }

        /** Eine Zeile ab dem ersten Nicht-Leerzeichen. */
        fun line() {
            val le = lineEnd()
            when {
                s[pos] == '#' || startsWith("//") -> emit(TsTokens.COMMENT, le)
                startsWith("/*") -> blockComment()
                s[pos] == '[' -> condition(le)
                s[pos] == '}' -> { emit(TsTokens.RBRACE, pos + 1); rest(le) }
                startsWith("@import") -> import(le)
                startsWith("<INCLUDE_TYPOSCRIPT:") -> emit(TsTokens.INCLUDE, le)
                else -> statement(le)
            }
            // Was noch übrig ist (z. B. \r), als Leerraum
            if (pos < le) emit(TokenType.WHITE_SPACE, le)
        }

        /** Rest der Zeile: Leerraum, dann - egal was - Kommentar. */
        fun rest(le: Int) {
            blanks()
            if (pos >= le) return
            if (startsWith("/*")) { blockComment(); return }
            emit(TsTokens.COMMENT, le)
        }

        /** Endet erst an einer Zeile, die auf `*` + `/` endet (wie TYPO3). */
        fun blockComment() {
            fun endsWithClose(from: Int, le: Int) = le - from >= 2 && s[le - 2] == '*' && s[le - 1] == '/'
            val first = lineEnd()
            // Erste Zeile: `/*` selbst zählt nicht zum Ende
            if (endsWithClose(pos + 2, first)) { emit(TsTokens.BLOCK_COMMENT, first); return }
            var ls = nextLine(first)
            while (ls < limit) {
                val le = lineEnd(ls)
                if (endsWithClose(ls, le)) { emit(TsTokens.BLOCK_COMMENT, le); return }
                ls = nextLine(le)
            }
            emit(TsTokens.BLOCK_COMMENT, limit)
        }

        fun nextLine(le: Int): Int {
            var i = le
            while (i < limit && s[i] != '\n') i++
            return if (i < limit) i + 1 else limit
        }

        fun condition(le: Int) {
            emit(TsTokens.CONDITION_START, pos + 1)
            for (kw in listOf("ELSE]", "END]", "GLOBAL]")) {
                if (pos + kw.length <= le && s.subSequence(pos, pos + kw.length).toString().equals(kw, ignoreCase = true)) {
                    emit(TsTokens.CONDITION_KEYWORD, pos + kw.length - 1)
                    emit(TsTokens.CONDITION_END, pos + 1)
                    rest(le)
                    return
                }
            }
            var depth = 1
            var i = pos
            var quote: Char? = null
            while (i < le) {
                val c = s[i]
                if (quote != null) { if (c == quote) quote = null }
                else if (c == '"' || c == '\'') quote = c
                else if (c == '[') depth++
                else if (c == ']') { depth--; if (depth == 0) break }
                i++
            }
            emit(TsTokens.CONDITION_BODY, i)
            if (i < le) {
                emit(TsTokens.CONDITION_END, i + 1)
                rest(le)
            }
        }

        fun import(le: Int) {
            emit(TsTokens.IMPORT_KEYWORD, pos + "@import".length)
            blanks()
            if (pos >= le) return
            val q = s[pos]
            if (q != '\'' && q != '"') { emit(TsTokens.COMMENT, le); return }
            var i = pos + 1
            while (i < le && s[i] != q) i++
            emit(TsTokens.IMPORT_PATH, if (i < le) i + 1 else le)
            rest(le)
        }

        /** Pfad (`a.b\.c.d`) bis zu einem Stoppzeichen; liefert false, wenn nichts gelesen wurde. */
        fun path(le: Int): Boolean {
            val begin = pos
            var segStart = pos
            var i = pos
            fun flush(to: Int) {
                if (to > segStart) { pos = segStart; emit(TsTokens.IDENTIFIER, to) }
            }
            while (i < le) {
                val c = s[i]
                if (i > begin && (c == ' ' || c == '\t' || c == '=' || c == '<' || c == '>' || c == '{' || c == '(' ||
                        (c == ':' && i + 1 < le && s[i + 1] == '='))) break
                if (c == '\\' && i + 1 < le && s[i + 1] == '.') { i += 2; continue }
                if (c == '.') {
                    flush(i)
                    pos = i
                    emit(TsTokens.DOT, i + 1)
                    segStart = i + 1
                    i++
                    continue
                }
                i++
            }
            flush(i)
            pos = i
            return i > begin
        }

        fun statement(le: Int) {
            if (!path(le)) { emit(TokenType.BAD_CHARACTER, pos + 1); return }
            blanks()
            if (pos >= le) return
            when {
                startsWith("=<") -> { emit(TsTokens.OP_REFERENCE, pos + 2); source(le) }
                s[pos] == '=' -> { emit(TsTokens.OP_ASSIGN, pos + 1); blanks(); value(le) }
                s[pos] == '{' -> {
                    emit(TsTokens.LBRACE, pos + 1)
                    blanks()
                    if (pos < le && s[pos] == '}') emit(TsTokens.RBRACE, pos + 1)
                    rest(le)
                }
                s[pos] == '>' -> { emit(TsTokens.OP_UNSET, pos + 1); rest(le) }
                s[pos] == '<' -> { emit(TsTokens.OP_COPY, pos + 1); source(le) }
                s[pos] == '(' -> multiline(le)
                startsWith(":=") -> modification(le)
                else -> rest(le)
            }
        }

        fun source(le: Int) {
            blanks()
            if (pos >= le) return
            if (s[pos] == '#' || startsWith("//") || startsWith("/*")) { rest(le); return }
            if (s[pos] == '.') emit(TsTokens.DOT, pos + 1)
            path(le)
            rest(le)
        }

        /** Wert bis [to], `{$…}` als eigene Tokens. Ein Wert, der nur ein Objekttyp ist, wird OBJECT_TYPE. */
        fun value(to: Int) {
            if (pos >= to) return
            var valueEnd = to
            while (valueEnd > pos && (s[valueEnd - 1] == ' ' || s[valueEnd - 1] == '\t')) valueEnd--
            if (isObjectType(pos, valueEnd)) {
                emit(TsTokens.OBJECT_TYPE, valueEnd)
                emit(TokenType.WHITE_SPACE, to)
                return
            }
            var i = pos
            while (i < to) {
                if (s[i] == '{' && i + 1 < to && s[i + 1] == '$') {
                    val close = indexOf('}', i + 2, to)
                    if (close < 0) break
                    emit(TsTokens.VALUE, i)
                    emit(TsTokens.CONSTANT, close + 1)
                    i = close + 1
                    continue
                }
                i++
            }
            emit(TsTokens.VALUE, to)
        }

        fun indexOf(c: Char, from: Int, to: Int): Int {
            for (k in from until to) if (s[k] == c) return k
            return -1
        }

        fun isObjectType(from: Int, to: Int): Boolean {
            if (to - from < 3 || !s[from].isUpperCase()) return false
            for (k in from until to) {
                val c = s[k]
                if (!(c in 'A'..'Z' || c == '_' || c in '0'..'9')) return false
            }
            return true
        }

        fun multiline(le: Int) {
            emit(TsTokens.MULTILINE_START, pos + 1)
            // Einzeilig: `foo ( bar )`
            var e = le
            while (e > pos && (s[e - 1] == ' ' || s[e - 1] == '\t')) e--
            if (e > pos && s[e - 1] == ')') {
                value(e - 1)
                emit(TsTokens.MULTILINE_END, e)
                return
            }
            // Mehrzeilig: bis zu einer Zeile, die (nach Leerraum) mit `)` beginnt
            var lineStart = nextLine(le)
            var closing = -1
            while (lineStart < limit) {
                var k = lineStart
                while (k < limit && (s[k] == ' ' || s[k] == '\t')) k++
                if (k < limit && s[k] == ')') { closing = k; break }
                lineStart = nextLine(lineEnd(lineStart))
            }
            if (closing < 0) {
                value(limit)
                return
            }
            // Wert bis vor den Zeilenumbruch der schließenden Zeile
            var valueEnd = lineStart
            if (valueEnd > pos && s[valueEnd - 1] == '\n') valueEnd--
            if (valueEnd > pos && s[valueEnd - 1] == '\r') valueEnd--
            value(valueEnd)
            emit(TokenType.WHITE_SPACE, closing)
            emit(TsTokens.MULTILINE_END, closing + 1)
            rest(lineEnd())
        }

        fun modification(le: Int) {
            emit(TsTokens.OP_MODIFY, pos + 2)
            blanks()
            val paren = indexOf('(', pos, le)
            if (paren < 0) { emit(TsTokens.FUNCTION_NAME, le); return }
            emit(TsTokens.FUNCTION_NAME, paren)
            emit(TsTokens.LPAREN, paren + 1)
            var depth = 0
            var i = pos
            while (i < le) {
                if (s[i] == '(') depth++
                else if (s[i] == ')') { if (depth == 0) break; depth-- }
                i++
            }
            value(i)
            if (i < le) {
                emit(TsTokens.RPAREN, i + 1)
                rest(le)
            }
        }
    }
}
