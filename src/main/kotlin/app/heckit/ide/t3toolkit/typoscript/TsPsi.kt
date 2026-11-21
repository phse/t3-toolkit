package app.heckit.ide.t3toolkit.typoscript

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.Icon

class TsFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, TsLanguage) {
    override fun getFileType() = TsFileType.INSTANCE
    override fun toString() = "TypoScript file"

    val statements: List<PsiElement> get() = children.filter { it is TsStatement || it is TsCondition || it is TsImport || it is TsInclude }

    /** Konstanten-Datei (`constants.typoscript`, `*.constants.typoscript`, `constants.txt` …). */
    val isConstants: Boolean get() = TsFiles.isConstants(name)
    /** Page- oder User-TSconfig. */
    val isTsConfig: Boolean get() = TsFiles.isTsConfig(virtualFile?.path ?: name)
}

object TsFiles {
    fun isConstants(name: String) = name.lowercase().let { "constants" in it || it == "ext_conf_template.txt" }
    fun isTsConfig(path: String): Boolean {
        val p = path.lowercase()
        val name = p.substringAfterLast('/')
        return name.endsWith(".tsconfig") || "pagets" in name || "userts" in name || "tsconfig" in name ||
            "/tsconfig/" in p || "/pagetsconfig/" in p || "/usertsconfig/" in p
    }
}

/** `page.10.templateName` - Segmente ohne die Punkte; ein führender Punkt macht den Pfad relativ (`< .lib`). */
class TsObjectPath(node: ASTNode) : ASTWrapperPsiElement(node) {
    val segments: List<String> get() = node.getChildren(null).filter { it.elementType == TsTokens.IDENTIFIER }.map { it.text }
    val segmentElements: List<PsiElement> get() = node.getChildren(null).filter { it.elementType == TsTokens.IDENTIFIER }.map { it.psi }
    val isRelative: Boolean get() = node.firstChildNode?.elementType == TsTokens.DOT
    val isSource: Boolean get() = (parent as? TsStatement)?.path != this

    /** Absoluter Pfad: Ziel einer Anweisung samt umschließender Blöcke, Quelle einer Kopie ggf. relativ zum Block. */
    fun absoluteSegments(): List<String> {
        val statement = parent as? TsStatement ?: return segments
        return if (!isSource) statement.fullSegments()
        else if (isRelative) statement.prefixSegments() + segments else segments
    }

    /** Absoluter Pfad bis einschließlich [segment]. */
    fun absoluteSegmentsUpTo(segment: PsiElement): List<String> {
        val all = absoluteSegments()
        val idx = segmentElements.indexOf(segment)
        if (idx < 0) return all
        return all.dropLast(segments.size - idx - 1)
    }
}

class TsValue(node: ASTNode) : ASTWrapperPsiElement(node) {
    val constants: List<PsiElement> get() = node.getChildren(null).filter { it.elementType == TsTokens.CONSTANT }.map { it.psi }
}

/** Anweisung mit Pfad links: Zuweisung, Kopie, Referenz, Löschen, Funktion, mehrzeiliger Wert, Block. */
open class TsStatement(node: ASTNode) : ASTWrapperPsiElement(node) {
    val kind: IElementType get() = node.elementType
    val path: TsObjectPath? get() = findChildByClass(TsObjectPath::class.java)
    val sourcePath: TsObjectPath? get() = PsiTreeUtil.getChildrenOfType(this, TsObjectPath::class.java)?.getOrNull(1)
    val value: TsValue? get() = findChildByClass(TsValue::class.java)
    val valueText: String get() = value?.text?.trim().orEmpty()
    val functionName: String? get() = node.findChildByType(TsTokens.FUNCTION_NAME)?.text?.trim()

    /** Pfade der umschließenden Blöcke, außen zuerst. */
    fun prefixSegments(): List<String> {
        val result = ArrayList<String>()
        var block = PsiTreeUtil.getParentOfType(this, TsBlock::class.java, true)
        while (block != null) {
            result.addAll(0, block.path?.segments.orEmpty())
            block = PsiTreeUtil.getParentOfType(block, TsBlock::class.java, true)
        }
        return result
    }

    fun fullSegments(): List<String> = prefixSegments() + path?.segments.orEmpty()
    fun fullPath(): String = fullSegments().joinToString(".")

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = path?.text.orEmpty()
        override fun getLocationString(): String? = when (kind) {
            TsElements.ASSIGNMENT -> "= " + valueText.take(60)
            TsElements.COPY -> "< " + sourcePath?.text.orEmpty()
            TsElements.REFERENCE -> "=< " + sourcePath?.text.orEmpty()
            TsElements.UNSET -> ">"
            TsElements.MODIFICATION -> ":= " + functionName.orEmpty() + "(…)"
            TsElements.MULTILINE_ASSIGNMENT -> "( … )"
            else -> null
        }
        override fun getIcon(unused: Boolean): Icon? = null
    }
}

class TsBlock(node: ASTNode) : TsStatement(node) {
    val statements: List<PsiElement> get() = children.filter { it is TsStatement || it is TsCondition || it is TsImport || it is TsInclude }
    val isClosed: Boolean get() = node.findChildByType(TsTokens.RBRACE) != null
}

class TsCondition(node: ASTNode) : ASTWrapperPsiElement(node) {
    val keyword: String? get() = node.findChildByType(TsTokens.CONDITION_KEYWORD)?.text?.uppercase()
    val body: PsiElement? get() = node.findChildByType(TsTokens.CONDITION_BODY)?.psi
    /** `[END]` und `[GLOBAL]` beenden einen Bedingungsbereich. */
    val isTerminator: Boolean get() = keyword == "END" || keyword == "GLOBAL"
}

class TsImport(node: ASTNode) : ASTWrapperPsiElement(node) {
    val pathElement: PsiElement? get() = node.findChildByType(TsTokens.IMPORT_PATH)?.psi
    val importPath: String? get() = pathElement?.text?.trim()?.trim('\'', '"')
}

/** `<INCLUDE_TYPOSCRIPT: source="FILE:EXT:…" …>` (veraltet, ab TYPO3 v12 durch `@import` ersetzt). */
class TsInclude(node: ASTNode) : ASTWrapperPsiElement(node) {
    /** `FILE:EXT:…` bzw. `DIR:EXT:…` ohne Präfix samt Spanne im Element. */
    val source: Pair<String, IntRange>? get() {
        val m = SOURCE.find(text) ?: return null
        val g = m.groups[2]!!
        return g.value to g.range
    }
    val isDirectory: Boolean get() = SOURCE.find(text)?.groupValues?.get(1)?.equals("DIR", true) == true
    val extensions: List<String> get() = EXTENSIONS.find(text)?.groupValues?.get(1)?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }.orEmpty()

    private companion object {
        val SOURCE = Regex("""source\s*=\s*"\s*(FILE|DIR)\s*:\s*([^"]*)"""", RegexOption.IGNORE_CASE)
        val EXTENSIONS = Regex("""extensions\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
    }
}
