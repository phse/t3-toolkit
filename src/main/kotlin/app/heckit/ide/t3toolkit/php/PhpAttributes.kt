package app.heckit.ide.t3toolkit.php

import com.intellij.openapi.editor.Document
import com.jetbrains.php.lang.psi.elements.PhpClass

/** Ein PHP-Attribut mit benannten Argumenten (Werte schon als PHP-Ausdruck, siehe [str], [cls], [list]). */
data class PhpAttribute(val fqn: String, val args: List<Pair<String, String>> = emptyList())

object PhpAttributes {
    fun str(value: String) = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
    fun list(values: List<String>) = values.joinToString(", ", "[", "]") { str(it) }

    private val CLASS_REF = Regex("""%%([A-Za-z0-9_\\\\]+)%%""")

    /** Klassenreferenz `Foo::class`; der Name wird beim Einfügen aufgelöst und importiert. */
    fun cls(fqn: String) = "%%" + fqn.trimStart('\\') + "%%::class"

    private fun render(attr: PhpAttribute, names: Map<String, String>): String {
        val name = names.getValue(attr.fqn)
        if (attr.args.isEmpty()) return "#[$name]"
        val args = attr.args.joinToString(", ") { "${it.first}: ${it.second}" }
        return "#[$name(" + CLASS_REF.replace(args) { names.getValue(it.groupValues[1]) } + ")]"
    }

    /** Text einer neuen Datei: Attribute rendern, Imports sammeln. */
    fun renderStandalone(attrs: List<PhpAttribute>, imports: MutableSet<String>): List<String> {
        val names = HashMap<String, String>()
        (attrs.map { it.fqn } + attrs.flatMap { a -> a.args.flatMap { CLASS_REF.findAll(it.second).map { m -> m.groupValues[1] } } })
            .forEach { imports += it; names[it] = it.substringAfterLast('\\') }
        return attrs.map { render(it, names) }
    }

    /** Schreibt Attribute über die Klasse und ergänzt die use-Anweisungen (bei Namenskonflikt voll qualifiziert). */
    fun addToClass(document: Document, cls: PhpClass, attrs: List<PhpAttribute>) {
        val text = document.charsSequence
        val classStart = cls.textRange.startOffset
        val head = text.subSequence(0, classStart).toString()
        val names = HashMap<String, String>() // fqn → im Code verwendeter Name
        val newImports = ArrayList<String>()
        val refs = attrs.flatMap { a -> a.args.flatMap { CLASS_REF.findAll(it.second).map { m -> m.groupValues[1] } } }
        for (fqn in attrs.map { it.fqn } + refs) {
            if (fqn in names) continue
            val short = fqn.substringAfterLast('\\')
            names[fqn] = when {
                Regex("""(?m)^use\s+\\?${Regex.escape(fqn)}\s*;""").containsMatchIn(head) -> short
                Regex("""(?m)^use\s+[^;]*\\${Regex.escape(short)}\s*;""").containsMatchIn(head) || cls.name == short -> "\\" + fqn
                else -> { newImports += fqn; short }
            }
        }
        // Konflikt zwischen zwei neuen Imports mit gleichem Kurznamen
        newImports.groupBy { it.substringAfterLast('\\') }.values.filter { it.size > 1 }.flatten().forEach {
            newImports -= it; names[it] = "\\" + it
        }

        val lines = attrs.map { render(it, names) }
        val doc = cls.docComment
        if (doc != null && doc.textRange.startOffset >= classStart) {
            document.insertString(doc.textRange.endOffset, "\n" + lines.joinToString("\n"))
        } else {
            document.insertString(classStart, lines.joinToString("\n") + "\n")
        }
        if (newImports.isEmpty()) return
        val sorted = newImports.sorted().joinToString("\n") { "use $it;" }
        val lastUse = Regex("""(?m)^use\s+[^;]+;[ \t]*$""").findAll(head).lastOrNull()
        val ns = Regex("""(?m)^namespace\s+[^;]+;[ \t]*$""").find(head)
        when {
            lastUse != null -> document.insertString(lastUse.range.last + 1, "\n" + sorted)
            ns != null -> document.insertString(ns.range.last + 1, "\n\n" + sorted)
            else -> {
                val php = head.indexOf("<?php")
                document.insertString(if (php >= 0) php + 5 else 0, "\n\n" + sorted)
            }
        }
    }
}
