<?php

defined('TYPO3') or die();

$GLOBALS['TYPO3_CONF_VARS']['SYS']['fluid']['namespaces']['demo'] = ['Acme\\DemoSite\\ViewHelpers'];
$GLOBALS['TYPO3_CONF_VARS']['EXTCONF']['fluid_components']['namespaces']['Acme\\DemoSite\\Components'] =
    \TYPO3\CMS\Core\Utility\ExtensionManagementUtility::extPath('demo_site', 'Resources/Private/Components');
