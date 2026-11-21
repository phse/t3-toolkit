package app.heckit.ide.t3toolkit.fluid

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as D
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.util.TextRange
import com.intellij.icons.AllIcons
import com.intellij.lang.html.HTMLLanguage
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import javax.swing.Icon

object FluidColors {
    val BRACES = createTextAttributesKey("T3FLUID_BRACES", D.METADATA)
    val VIEWHELPER = createTextAttributesKey("T3FLUID_VIEWHELPER", D.FUNCTION_DECLARATION)
    val VARIABLE = createTextAttributesKey("T3FLUID_VARIABLE", D.INSTANCE_FIELD)
    val PROPERTY = createTextAttributesKey("T3FLUID_PROPERTY", D.STATIC_FIELD)
    val ARGUMENT = createTextAttributesKey("T3FLUID_ARGUMENT", com.intellij.openapi.editor.XmlHighlighterColors.HTML_ATTRIBUTE_NAME)
    val STRING = createTextAttributesKey("T3FLUID_STRING", D.STRING)
    val NUMBER = createTextAttributesKey("T3FLUID_NUMBER", D.NUMBER)
    val KEYWORD = createTextAttributesKey("T3FLUID_KEYWORD", D.KEYWORD)
    val OPERATOR = createTextAttributesKey("T3FLUID_OPERATOR", D.OPERATION_SIGN)

    fun of(kind: FluidInline.Kind): TextAttributesKey = when (kind) {
        FluidInline.Kind.BRACE -> BRACES
        FluidInline.Kind.VIEWHELPER -> VIEWHELPER
        FluidInline.Kind.VARIABLE -> VARIABLE
        FluidInline.Kind.PROPERTY -> PROPERTY
        FluidInline.Kind.ARGUMENT -> ARGUMENT
        FluidInline.Kind.STRING -> STRING
        FluidInline.Kind.NUMBER -> NUMBER
        FluidInline.Kind.KEYWORD -> KEYWORD
        FluidInline.Kind.OPERATOR -> OPERATOR
    }
}

/** Färbt Fluid-Inline-Ausdrücke in Texten und Attributwerten von Fluid-Templates. */
class FluidInlineAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is XmlText && element !is XmlAttributeValue) return
        val file = element.containingFile ?: return
        if (InjectedLanguageManager.getInstance(element.project).isInjectedFragment(file)) return
        if (FluidInline.otherFluidPluginActive() || !FluidInline.isFluidFile(file)) return
        val range = element.textRange
        val text = file.viewProvider.contents
        val tagName = PsiTreeUtil.getParentOfType(element, XmlTag::class.java)?.name?.lowercase()
        val strict = element is XmlText && (tagName == "script" || tagName == "style")
        for (e in FluidInline.expressions(text, range.startOffset, range.endOffset, strict)) {
            for (p in FluidInline.parts(text, e)) {
                if (p.end <= p.start) continue
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(TextRange(p.start, p.end))
                    .textAttributes(FluidColors.of(p.kind)).create()
            }
        }
    }
}

class FluidColorSettingsPage : ColorSettingsPage {
    private val descriptors = arrayOf(
        AttributesDescriptor("Braces", FluidColors.BRACES),
        AttributesDescriptor("ViewHelper", FluidColors.VIEWHELPER),
        AttributesDescriptor("Variable", FluidColors.VARIABLE),
        AttributesDescriptor("Property (item.title)", FluidColors.PROPERTY),
        AttributesDescriptor("Argument name", FluidColors.ARGUMENT),
        AttributesDescriptor("String", FluidColors.STRING),
        AttributesDescriptor("Number", FluidColors.NUMBER),
        AttributesDescriptor("Keyword (true, and …)", FluidColors.KEYWORD),
        AttributesDescriptor("Operator (->, == …)", FluidColors.OPERATOR),
    )

    override fun getIcon(): Icon = AllIcons.FileTypes.Html
    override fun getHighlighter(): SyntaxHighlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(HTMLLanguage.INSTANCE, null, null)
    override fun getDemoText() = """
        <f:layout name="Default" />
        <f:section name="Main">
          <h1><fb>{</fb><fv>page</fv>.<fp>title</fp><fb>}</fb></h1>
          <f:for each="<fb>{</fb><fv>items</fv><fb>}</fb>" as="item">
            <a href="<fb>{</fb><fvh>f:uri.page</fvh>(<fa>pageUid</fa>: <fv>item</fv>.<fp>uid</fp>)<fb>}</fb>"><fb>{</fb><fv>item</fv>.<fp>title</fp> <fo>-></fo> <fvh>f:format.case</fvh>(<fa>mode</fa>: <fs>'upper'</fs>)<fb>}</fb></a>
          </f:for>
          <fb>{</fb><fvh>f:if</fvh>(<fa>condition</fa>: <fv>count</fv> <fo>></fo> <fn>3</fn> <fk>and</fk> <fv>show</fv>, <fa>then</fa>: <fs>'many'</fs>)<fb>}</fb>
        </f:section>
    """.trimIndent()
    override fun getAdditionalHighlightingTagToDescriptorMap() = mapOf(
        "fb" to FluidColors.BRACES, "fvh" to FluidColors.VIEWHELPER, "fv" to FluidColors.VARIABLE, "fp" to FluidColors.PROPERTY,
        "fa" to FluidColors.ARGUMENT, "fs" to FluidColors.STRING, "fn" to FluidColors.NUMBER, "fk" to FluidColors.KEYWORD, "fo" to FluidColors.OPERATOR,
    )
    override fun getAttributeDescriptors() = descriptors
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getDisplayName() = "Fluid (TYPO3)"
}
