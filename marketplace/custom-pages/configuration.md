## Settings

**Settings | Languages & Frameworks | T3 Toolkit** (per project, stored in `.idea/t3toolkit.xml`)

| Option | Values | Default |
| --- | --- | --- |
| Detect namespaces registered in `ext_localconf.php` | on / off | on |
| Additional | one prefix per line: `my = Vendor\Ext\ViewHelpers, Vendor\Other\ViewHelpers` | – |
| Additional calls with a label key | one call per line, see [Translations](#translations) | – |

The settings page lists the TYPO3 defaults and the detected namespaces.

| Other pages | What |
| --- | --- |
| *Editor \| Color Scheme \| TypoScript* | colours of paths, known properties, numeric keys, values, object types, constants, conditions |
| *Editor \| Color Scheme \| Fluid (TYPO3)* | colours inside `{…}`: braces, variables, properties, ViewHelpers, arguments, strings, operators |
| *Editor \| Code Style \| TypoScript* | indentation (default 2 spaces like the TYPO3 core) |
| *Editor \| Inspections \| T3 Toolkit* | all checks, e.g. undefined constants (weak warning), deprecated `INCLUDE_TYPOSCRIPT` |

If another TypoScript or Fluid plugin is installed, T3 Toolkit shows a one-time notice: both register `.typoscript` and
colour Fluid expressions - disable one of them.

## TypoScript

| Topic | Details |
| --- | --- |
| File types | `.typoscript`, `.tsconfig`, `.t3s`, `setup.txt`, `constants.txt`, `ext_typoscript_setup.txt` |
| Syntax | TYPO3 v12+: one statement per line; text after `{`, `}`, `>`, a copy source or a condition is a comment; multi-line values end at a line starting with `)` |
| Kinds | constants: file name contains `constants`; TSconfig: `.tsconfig`, `pagets`/`userts` in the name or a `TsConfig` folder; everything else is setup |
| Types | from `= TYPE` (also `PAGE`, `CONFIG`), copies and references (`<`, `=<`, also relative `.x`), data processors by alias or class; otherwise from the parent's property |
| Reference | PAGE, CONFIG, all content objects (TEXT, COA, FLUIDTEMPLATE, PAGEVIEW, CONTENT, RECORDS, FILES, IMAGE, HMENU, USER …), stdWrap, typolink, if, select, imgResource, parseFunc, data processors (menu, files, database-query, split, gallery …), plugin configuration (view, persistence, settings), TSconfig (mod, TCEFORM, TCEMAIN, TCAdefaults, options) |
| Constants `{$…}` | constants files and site settings: `Configuration/Sets/*/settings.definitions.yaml`, `settings.yaml` of sets and sites |
| `@import` | `EXT:` and relative paths (`./`, `../`), `*` patterns; a pattern only needs its folder to exist |

## TCA

Recognized in `Configuration/TCA/<table>.php` (`return [...]`), `Configuration/TCA/Overrides/*.php`,
`$GLOBALS['TCA']['<table>'][…] = …` and `ExtensionManagementUtility::addTCAcolumns('<table>', [...])` (also via a variable).

| Position | Completion / navigation |
| --- | --- |
| keys | table, `ctrl`, `ctrl.enablecolumns`, column, `config` (by `type`), `types`, `palettes`, `items` entries, `columnsOverrides` |
| `type`, `renderType`, `format` | field types, render types and formats of that type |
| `foreign_table`, `allowed` | tables with a TCA file → the file |
| `MM` | tables from `ext_tables.sql` → `CREATE TABLE` |
| `foreign_field`, `foreign_sortby`, `foreign_label` … | fields of the foreign table → the field |
| `ctrl.label`, `sortby`, `languageField` …, `enablecolumns` | fields of the table → the field |
| `showitem` | fields, `--palette--;;name`, `--div--;Label`, `--linebreak--` → field or palette |
| `eval`, `l10n_mode`, `l10n_display`, `onChange` | known values |

## Fluid inline syntax

Expressions are recognized in text and attribute values of Fluid templates (files under `Resources/Private/` or with a
Fluid namespace or `<f:` tag). Not touched: `{{ … }}` (Vue, Angular), `${…}` and JavaScript in `<script>` unless the
expression is clearly Fluid (`{settings.url}`, `{x -> f:y()}`). Variables offered after `{` come from `f:for`/`f:variable`/
`f:alias`, component parameters, `f:render` arguments of partials, the Extbase action (`assign()`, `assignMultiple()`) and
TypoScript (`variables`, `dataProcessing … as`).

Extbase actions: `Vendor\Ext\Controller\FooController::listAction()` ↔ `Resources/Private/Templates/Foo/List.html`
(own extension and TypoScript template root paths).

## Fluid namespaces

Resolved like TYPO3; the last registered wins.

| Source | Example |
| --- | --- |
| TYPO3 defaults | `f`, `core` |
| `ext_localconf.php` | `$GLOBALS['TYPO3_CONF_VARS']['SYS']['fluid']['namespaces']['my'][] = 'Vendor\\Ext\\ViewHelpers';` |
| Settings | *Additional* |
| Template | `{namespace my=Vendor\Ext\ViewHelpers}`, `xmlns:my="http://typo3.org/ns/Vendor/Ext/ViewHelpers"` |

## Translations

`LLL:EXT:…xlf:key` is recognized in every file. Short keys are recognized in these calls:

| Built in | Key | Extension |
| --- | --- | --- |
| `*:translate` (`f:translate` and any own `translate` ViewHelper) | `key`, `id` | `extensionName` |
| `LocalizationUtility::translate()` | 1st argument / `key:` | 2nd argument / `extensionName:` |

Own calls go into *Additional calls with a label key*, one per line with the arguments in brackets:

| Part | Meaning |
| --- | --- |
| `key` (or `id`) | the label key |
| a name containing `extension` | extension key or UpperCamelCase name |
| `_` | argument to skip |
| `a\|b` | alternative names |

| Kind | Example | Matched by |
| --- | --- | --- |
| ViewHelper | `my:label(key\|id, extensionName)`, `*:label(key)` | attribute names (tag and inline syntax) |
| Static method | `MyUtility::translate(key, extension)` | position or named argument |
| Instance method | `->label(key)` | position or named argument |
| Function | `my_translate(_, key)` | position or named argument |

Only plain string literals are used as keys (no concatenation). Without an extension the key is looked up in
`Resources/Private/Language/locallang.xlf` of the own extension, then of all other extensions.

## Fluid Components

| Source | Example |
| --- | --- |
| `ext_localconf.php` | `$GLOBALS['TYPO3_CONF_VARS']['EXTCONF']['fluid_components']['namespaces']['Vendor\\Ext\\Components'] = …;` |
| Convention | `Vendor\Ext\Components` → `Resources/Private/Components` |

Component files: `<Name>/<Name>.html`, related `<Name>.fixture.json`, styles and scripts are offered via *Navigate | Related Symbol*.

## Template root paths

| Kind | Looked up in |
| --- | --- |
| Partials, layouts, templates | `Resources/Private/Partials`, `Layouts`, `Templates` of the own extension |
| | `partialRootPaths`, `layoutRootPaths`, `templateRootPaths` from TypoScript |
| | `EXT:` paths of other extensions |

Missing partials and layouts are only reported where the root folders are known.

## Extension detection

| Source | Key |
| --- | --- |
| `composer.json` | `extra.typo3/cms.extension-key` |
| `ext_emconf.php` | folder name |

Everything is read from PSI and cached until the next change; TypoScript paths have their own file index.

## Services.yaml quick-fixes

| Tag / option | Attribute | TYPO3 |
| --- | --- | --- |
| `event.listener` | `#[AsEventListener]` | 13.0+ |
| `console.command` | `#[AsCommand]` | 12.4.8+, not with `schedulable: false` |
| `messenger.message_handler` | `#[AsMessageHandler]` | 13.0+ |
| `backend.controller` | `#[AsController]` / `#[Controller]` | 13.0+ / 12.x |
| `public: true` | `#[Autoconfigure(public: true)]` | 12+ |

Requires `autoconfigure: true`. The entry is removed only if `resource` still finds the class.
