## Settings

**Settings | Languages & Frameworks | T3 Toolkit** (per project, stored in `.idea/t3toolkit.xml`)

| Option | Values | Default |
| --- | --- | --- |
| Detect namespaces registered in `ext_localconf.php` | on / off | on |
| Additional | one prefix per line: `my = Vendor\Ext\ViewHelpers, Vendor\Other\ViewHelpers` | – |
| Additional calls with a label key | one call per line, see [Translations](#translations) | – |

The settings page lists the TYPO3 defaults and the detected namespaces.

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

No own index: everything is read from PSI and cached until the next change.

## Services.yaml quick-fixes

| Tag / option | Attribute | TYPO3 |
| --- | --- | --- |
| `event.listener` | `#[AsEventListener]` | 13.0+ |
| `console.command` | `#[AsCommand]` | 12.4.8+, not with `schedulable: false` |
| `messenger.message_handler` | `#[AsMessageHandler]` | 13.0+ |
| `backend.controller` | `#[AsController]` / `#[Controller]` | 13.0+ / 12.x |
| `public: true` | `#[Autoconfigure(public: true)]` | 12+ |

Requires `autoconfigure: true`. The entry is removed only if `resource` still finds the class.
