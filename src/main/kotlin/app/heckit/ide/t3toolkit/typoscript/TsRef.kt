package app.heckit.ide.t3toolkit.typoscript

/**
 * TypoScript-Referenz (Typen, Eigenschaften, Wurzelobjekte) aus `/typoscript/tsref.txt`.
 * Eigenschaften mit Punkt (`special.value`) werden zu Hilfstypen (`DP:menu.special`), damit `special.` sie anbietet.
 */
object TsRef {
    class Prop(
        val name: String,
        /** Art des Werts: string, boolean, integer, wrap, getText oder ein Typname. */
        val valueType: String,
        /** Typ der Unter-Eigenschaften (`stdWrap`, `PAGE` …), sonst null. */
        var childType: String?,
        val values: List<String>,
        val description: String,
        val owner: String,
    )

    class Type(val name: String, val parent: String?) {
        val props = LinkedHashMap<String, Prop>()
    }

    private const val SETUP_ROOT = "@setup"
    private const val TSCONFIG_ROOT = "@tsconfig"
    private val PRIMITIVES = setOf("string", "boolean", "integer", "wrap", "getText")

    val types: Map<String, Type> by lazy { load() }

    /** Inhaltsobjekte mit kurzer Beschreibung - für `= ` an Stellen, die ein cObject erwarten. */
    val contentObjects: Map<String, String> = linkedMapOf(
        "TEXT" to "Text with stdWrap (value, field, data …)",
        "COA" to "Array of content objects, rendered in key order",
        "COA_INT" to "Like COA, but not cached",
        "FLUIDTEMPLATE" to "Renders a Fluid template",
        "PAGEVIEW" to "Renders a page with Fluid, template chosen by the backend layout",
        "CONTENT" to "Renders records from the database",
        "RECORDS" to "Renders given records",
        "CASE" to "Chooses an object by a key",
        "EXTBASEPLUGIN" to "Renders an Extbase plugin",
        "FILES" to "Renders files or file references",
        "IMAGE" to "Renders an <img> tag",
        "IMG_RESOURCE" to "Returns the URL of a processed image",
        "SVG" to "Renders an SVG",
        "HMENU" to "Hierarchical menu",
        "USER" to "Output of a PHP function",
        "USER_INT" to "Output of a PHP function, not cached",
        "LOAD_REGISTER" to "Sets registers",
        "RESTORE_REGISTER" to "Restores registers",
    )

    /** Datenprozessoren des Core: Alias → Klasse. */
    val dataProcessors: Map<String, String> = linkedMapOf(
        "menu" to "TYPO3\\CMS\\Frontend\\DataProcessing\\MenuProcessor",
        "files" to "TYPO3\\CMS\\Frontend\\DataProcessing\\FilesProcessor",
        "database-query" to "TYPO3\\CMS\\Frontend\\DataProcessing\\DatabaseQueryProcessor",
        "split" to "TYPO3\\CMS\\Frontend\\DataProcessing\\SplitProcessor",
        "comma-separated-value" to "TYPO3\\CMS\\Frontend\\DataProcessing\\CommaSeparatedValueProcessor",
        "flex-form" to "TYPO3\\CMS\\Frontend\\DataProcessing\\FlexFormProcessor",
        "gallery" to "TYPO3\\CMS\\Frontend\\DataProcessing\\GalleryProcessor",
        "language-menu" to "TYPO3\\CMS\\Frontend\\DataProcessing\\LanguageMenuProcessor",
        "site" to "TYPO3\\CMS\\Frontend\\DataProcessing\\SiteProcessor",
        "site-language" to "TYPO3\\CMS\\Frontend\\DataProcessing\\SiteLanguageProcessor",
        "record-transformation" to "TYPO3\\CMS\\Frontend\\DataProcessing\\RecordTransformationProcessor",
        "page-content" to "TYPO3\\CMS\\Frontend\\DataProcessing\\PageContentFetchingProcessor",
    )

    /** Funktionen für `:=`. */
    val functions: Map<String, String> = linkedMapOf(
        "addToList" to "Adds comma-separated values to the list",
        "removeFromList" to "Removes values from the list",
        "uniqueList" to "Removes duplicates from the list",
        "reverseList" to "Reverses the list",
        "sortList" to "Sorts the list (ascending, descending, numeric)",
        "prependString" to "Puts the string before the value",
        "appendString" to "Puts the string after the value",
        "removeString" to "Removes the string from the value",
        "replaceString" to "Replaces old|new in the value",
        "getEnv" to "Value of an environment variable",
    )

    /** Variablen und Funktionen in Bedingungen (Symfony Expression Language). */
    val conditionWords: Map<String, String> = linkedMapOf(
        "request" to "PSR-7 request (request.getQueryParams(), request.getNormalizedParams() …)",
        "page" to "Current page record (page[\"uid\"], page[\"doktype\"])",
        "site" to "Current site (site(\"identifier\"), site(\"rootPageId\"))",
        "siteLanguage" to "Current language (siteLanguage(\"languageId\"), siteLanguage(\"locale\"))",
        "tree" to "Page tree (tree.level, tree.rootLineIds, tree.rootLineParentIds, tree.pagelayout)",
        "frontend" to "Frontend user (frontend.user.isLoggedIn, frontend.user.userGroupList)",
        "backend" to "Backend user (backend.user.isAdmin, backend.user.isLoggedIn)",
        "workspace" to "Workspace (workspace.workspaceId, workspace.isLive)",
        "typo3" to "TYPO3 (typo3.version, typo3.branch, typo3.devIpMask)",
        "applicationContext" to "Application context, e.g. \"Development\"",
        "date" to "date(\"j\") - current date in PHP date() format",
        "like" to "like(value, \"pattern*\") - wildcard comparison",
        "ip" to "ip(\"127.0.0.1\") - compare the client IP",
        "compatVersion" to "compatVersion(\"13.4\") - TYPO3 version at least",
        "loginUser" to "loginUser(\"*\") - logged-in frontend user",
        "usergroup" to "usergroup(\"1,2\") - frontend user groups",
        "traverse" to "traverse(array, \"path/to/key\") - value from a nested array",
        "feature" to "feature(\"name\") - feature toggle",
        "getenv" to "getenv(\"NAME\") - environment variable",
        "and" to "Logical and", "or" to "Logical or", "not" to "Logical not",
        "in" to "Value in array", "matches" to "Regular expression",
    )

    private fun load(): Map<String, Type> {
        val text = TsRef::class.java.getResourceAsStream("/typoscript/tsref.txt")?.bufferedReader()?.readText().orEmpty()
        val result = LinkedHashMap<String, Type>()
        var current: Type? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") && !line.startsWith("#:")) continue
            when {
                line.startsWith("@type ") -> {
                    // "@type NAME" oder "@type NAME : PARENT" (NAME darf selbst einen Doppelpunkt enthalten, z. B. DP:menu)
                    val decl = line.removePrefix("@type ").trim()
                    val name = decl.substringBefore(" : ").trim()
                    val parent = decl.substringAfter(" : ", "").trim().ifEmpty { null }
                    current = result.getOrPut(name) { Type(name, parent) }
                }
                line.startsWith("@root ") -> {
                    val root = if (line.endsWith("tsconfig")) TSCONFIG_ROOT else SETUP_ROOT
                    current = result.getOrPut(root) { Type(root, null) }
                }
                else -> current?.let { addProp(result, it, line) }
            }
        }
        return result
    }

    private fun addProp(types: MutableMap<String, Type>, type: Type, line: String) {
        val name = line.substringBefore(':').trim()
        val rest = line.substringAfter(':')
        val spec = rest.substringBefore('|').trim()
        val description = rest.substringAfter('|', "").trim()
        val typeSpec = spec.substringBefore('=').trim()
        val values = spec.substringAfter('=', "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val valueType = typeSpec.substringBefore('/')
        val childType = when {
            '/' in typeSpec -> typeSpec.substringAfter('/')
            valueType in PRIMITIVES -> null
            else -> valueType
        }
        if ('.' in name) {
            // special.value → Hilfstyp "<Typ>.special" mit Eigenschaft value
            val head = name.substringBefore('.')
            val helperName = "${type.name}.$head"
            val headProp = type.props[head]
            val helper = types.getOrPut(helperName) { Type(helperName, headProp?.childType) }
            headProp?.childType = helperName
            helper.props[name.substringAfter('.')] = Prop(name.substringAfter('.'), valueType, childType, values, description, helperName)
            return
        }
        type.props[name] = Prop(name, valueType, childType, values, description, type.name)
    }

    fun rootType(tsConfig: Boolean) = if (tsConfig) TSCONFIG_ROOT else SETUP_ROOT
    fun isRoot(type: String?) = type == SETUP_ROOT || type == TSCONFIG_ROOT

    /** Eigene und geerbte Eigenschaften, eigene zuerst. */
    fun props(type: String?): List<Prop> {
        val result = LinkedHashMap<String, Prop>()
        var t = type?.let { types[it] }
        val seen = HashSet<String>()
        while (t != null && seen.add(t.name)) {
            t.props.values.forEach { result.putIfAbsent(it.name, it) }
            t = t.parent?.let { types[it] }
        }
        return result.values.toList()
    }

    /** Eigenschaft [name] von [type]: exakt, sonst Zahlen-Schlüssel (`#`), Muster (`tx_*`) oder beliebig (`*`). */
    fun prop(type: String?, name: String): Prop? {
        val all = props(type)
        all.firstOrNull { it.name == name }?.let { return it }
        if (name.isNotEmpty() && name.all { it.isDigit() }) all.firstOrNull { it.name == "#" }?.let { return it }
        all.firstOrNull { it.name.length > 1 && it.name.endsWith("*") && name.startsWith(it.name.dropLast(1)) }?.let { return it }
        return all.firstOrNull { it.name == "*" }
    }

    fun isKnownType(name: String) = name in types || name in contentObjects
}
