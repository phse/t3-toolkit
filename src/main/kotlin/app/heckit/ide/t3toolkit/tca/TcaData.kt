package app.heckit.ide.t3toolkit.tca

/** Schlüssel und Werte der TCA (TYPO3 v12+), knapp beschrieben. */
object TcaData {
    val tableKeys = linkedMapOf(
        "ctrl" to "Table settings (title, label, enable fields, language …)",
        "columns" to "Fields of the table",
        "types" to "Record types and the fields they show (showitem)",
        "palettes" to "Groups of fields shown in one row",
    )

    val ctrlKeys = linkedMapOf(
        "title" to "Table name shown in the backend (LLL:…)",
        "label" to "Field shown as record title",
        "label_alt" to "Fields used if label is empty (comma-separated)",
        "label_alt_force" to "Always show label_alt fields",
        "label_userFunc" to "Function that builds the record title",
        "formattedLabel_userFunc" to "Function that builds a formatted title",
        "descriptionColumn" to "Field with an editor note",
        "tstamp" to "Field with the modification time",
        "crdate" to "Field with the creation time",
        "delete" to "Field that marks deleted records (soft delete)",
        "enablecolumns" to "Fields for hidden, start/end time and access",
        "sortby" to "Field for manual sorting",
        "default_sortby" to "ORDER BY if there is no sortby field",
        "languageField" to "Field with the language (sys_language_uid)",
        "transOrigPointerField" to "Field pointing to the default language record (l10n_parent)",
        "transOrigDiffSourceField" to "Field with the default language state (l10n_diffsource)",
        "translationSource" to "Field pointing to the record the translation was made from",
        "origUid" to "Field with the uid of the original record (copies)",
        "versioningWS" to "Enable workspaces for the table",
        "iconfile" to "Icon of the table (EXT:…)",
        "typeicon_column" to "Field that selects the icon",
        "typeicon_classes" to "Icon identifiers per type value",
        "type" to "Field that selects the record type (types)",
        "searchFields" to "Fields searched in the backend (comma-separated)",
        "hideTable" to "Hide the table in the list module",
        "readOnly" to "Records cannot be edited",
        "adminOnly" to "Only admins can edit",
        "rootLevel" to "0 = pages only, 1 = root only, -1 = both",
        "security" to "Security settings (ignoreWebMountRestriction …)",
        "editlock" to "Field that locks the record for non-admins",
        "hideAtCopy" to "Hide copies",
        "prependAtCopy" to "Text prepended to the label of copies",
        "copyAfterDuplFields" to "Fields copied from the previous record",
        "useColumnsForDefaultValues" to "Fields taken from the previous record for new ones",
        "groupName" to "Group in the new record wizard",
        "previewRenderer" to "Class that renders the preview in the page module",
    )

    val enableColumnsKeys = linkedMapOf(
        "disabled" to "Hidden field",
        "starttime" to "Start time field",
        "endtime" to "End time field",
        "fe_group" to "Frontend user groups field",
    )

    val columnKeys = linkedMapOf(
        "label" to "Field label (LLL:…)",
        "description" to "Help text below the label",
        "config" to "Field configuration (type …)",
        "exclude" to "Field must be allowed for non-admin groups",
        "l10n_mode" to "Translation behaviour (exclude, prefixLangTitle)",
        "l10n_display" to "Display in translations (hideDiff, defaultAsReadonly)",
        "displayCond" to "Show the field only under a condition",
        "onChange" to "reload: reload the form when the value changes",
    )

    val typeKeys = linkedMapOf(
        "showitem" to "Fields, palettes (--palette--;;name) and tabs (--div--;Label)",
        "columnsOverrides" to "Change field configuration for this type",
        "subtype_value_field" to "Field that selects a subtype",
        "subtypes_addlist" to "Fields added per subtype",
        "subtypes_excludelist" to "Fields removed per subtype",
        "bitmask_value_field" to "Field with a bitmask",
        "previewRenderer" to "Preview renderer for this type",
        "creationOptions" to "Options of the new content element wizard",
    )

    val paletteKeys = linkedMapOf(
        "label" to "Palette label",
        "description" to "Palette description",
        "showitem" to "Fields of the palette (--linebreak-- for a new line)",
        "isHiddenPalette" to "Never show the palette",
    )

    val itemKeys = linkedMapOf(
        "label" to "Item label (LLL:…)",
        "value" to "Stored value",
        "icon" to "Icon identifier",
        "group" to "Item group (itemGroups)",
        "description" to "Description (selectCheckBox)",
    )

    val types = linkedMapOf(
        "input" to "Single line text",
        "text" to "Multi-line text, rich text, code",
        "check" to "Checkbox(es)",
        "radio" to "Radio buttons",
        "select" to "Select box, tree or check boxes",
        "group" to "Relations to records of any table",
        "folder" to "Relation to folders",
        "file" to "File references (FAL)",
        "inline" to "Child records (IRRE)",
        "flex" to "FlexForm (XML)",
        "none" to "Read-only display",
        "passthrough" to "Value not shown, but saved",
        "user" to "Own rendering",
        "imageManipulation" to "Image cropping",
        "slug" to "URL segment",
        "category" to "Categories",
        "language" to "Language selection",
        "datetime" to "Date and time",
        "email" to "Email address",
        "link" to "Link (page, URL, file …)",
        "number" to "Integer or decimal",
        "password" to "Password",
        "color" to "Color picker",
        "uuid" to "UUID",
        "json" to "JSON",
    )

    private val common = listOf("type", "default", "readOnly", "fieldControl", "fieldInformation", "fieldWizard", "behaviour")
    private val textual = listOf("size", "nullable", "required", "placeholder", "autocomplete", "eval", "search", "softref")

    val configKeys: Map<String, List<String>> = mapOf(
        "input" to common + textual + listOf("max", "min", "valuePicker", "mode", "is_in"),
        "text" to common + listOf("cols", "rows", "wrap", "max", "eval", "placeholder", "enableRichtext", "richtextConfiguration", "fixedFont",
            "enableTabulator", "format", "renderType", "nullable", "required", "search", "softref"),
        "check" to common + listOf("items", "cols", "renderType", "eval", "invertStateDisplay", "itemsProcFunc"),
        "radio" to common + listOf("items", "itemsProcFunc"),
        "select" to common + listOf("renderType", "items", "itemsProcFunc", "foreign_table", "foreign_table_where", "MM", "MM_match_fields",
            "MM_opposite_field", "size", "minitems", "maxitems", "autoSizeMax", "multiple", "exclusiveKeys", "treeConfig", "allowNonIdValues",
            "dbFieldLength", "disableNoMatchingValueElement", "fileFolderConfig", "itemGroups", "sortItems", "authMode", "appearance"),
        "group" to common + listOf("allowed", "foreign_table", "MM", "MM_match_fields", "MM_opposite_field", "size", "minitems", "maxitems",
            "autoSizeMax", "multiple", "prepend_tname", "elementBrowserEntryPoints", "filter", "suggestOptions", "hideSuggest", "hideMoveIcons"),
        "folder" to common + listOf("size", "minitems", "maxitems", "autoSizeMax", "elementBrowserEntryPoints"),
        "file" to common + listOf("allowed", "disallowed", "minitems", "maxitems", "appearance", "overrideChildTca"),
        "inline" to common + listOf("foreign_table", "foreign_field", "foreign_table_field", "foreign_match_fields", "foreign_sortby",
            "foreign_default_sortby", "foreign_label", "foreign_selector", "foreign_unique", "MM", "appearance", "minitems", "maxitems",
            "overrideChildTca", "filter", "symmetric_field", "symmetric_label", "symmetric_sortby", "customControls"),
        "flex" to common + listOf("ds", "ds_pointerField"),
        "none" to common + listOf("format", "size", "pass_content"),
        "passthrough" to common,
        "user" to common + listOf("renderType", "parameters"),
        "imageManipulation" to common + listOf("file_field", "allowedExtensions", "cropVariants"),
        "slug" to common + listOf("generatorOptions", "fallbackCharacter", "prependSlash", "eval", "appearance", "size"),
        "category" to common + listOf("relationship", "treeConfig", "minitems", "maxitems", "exclusiveKeys", "size"),
        "language" to common,
        "datetime" to common + listOf("format", "dbType", "range", "nullable", "required", "disableAgeDisplay", "size", "placeholder"),
        "email" to common + textual,
        "link" to common + textual + listOf("allowedTypes", "appearance"),
        "number" to common + textual + listOf("format", "range", "slider"),
        "password" to common + textual + listOf("hashed", "passwordPolicy"),
        "color" to common + textual + listOf("opacity", "valuePicker"),
        "uuid" to common + listOf("version", "enableCopyToClipboard", "size", "required"),
        "json" to common + listOf("cols", "rows", "enableCodeEditor", "placeholder", "required"),
    )

    val allConfigKeys: List<String> by lazy { configKeys.values.flatten().distinct().sorted() }

    val renderTypes = mapOf(
        "select" to listOf("selectSingle", "selectSingleBox", "selectCheckBox", "selectMultipleSideBySide", "selectTree"),
        "check" to listOf("default", "checkboxToggle", "checkboxLabeledToggle"),
        "text" to listOf("codeEditor", "textTable", "belayoutwizard"),
    )

    /** Feste Werte je Schlüssel (ohne Kontext). */
    val values = mapOf(
        "l10n_mode" to listOf("exclude", "prefixLangTitle"),
        "l10n_display" to listOf("hideDiff", "defaultAsReadonly"),
        "onChange" to listOf("reload"),
        "relationship" to listOf("oneToOne", "oneToMany", "manyToMany"),
        "dbType" to listOf("date", "datetime", "time"),
    )

    /** Werte, deren Bedeutung vom Typ abhängt. */
    val formatByType = mapOf(
        "datetime" to listOf("date", "datetime", "time", "timesec"),
        "number" to listOf("integer", "decimal"),
        "none" to listOf("date", "datetime", "time", "timesec", "int", "float", "number", "md5", "filesize", "user"),
        "text" to listOf("html", "css", "javascript", "json", "php", "sql", "typoscript", "xml"),
    )

    val evalValues = listOf("trim", "null", "upper", "lower", "alpha", "alphanum", "alphanum_x", "nospace", "md5", "num", "is_in",
        "domainname", "unique", "uniqueInPid", "int", "year")

    /** Schlüssel unter ctrl, deren Wert ein Feld der Tabelle ist. */
    val ctrlFieldKeys = setOf("label", "label_alt", "descriptionColumn", "tstamp", "crdate", "delete", "sortby", "languageField",
        "transOrigPointerField", "transOrigDiffSourceField", "translationSource", "origUid", "typeicon_column", "type", "searchFields", "editlock")
}
