<?php

defined('TYPO3') or die();

$GLOBALS['TCA']['tt_content']['columns']['tx_demosite_teaser'] = [
    'label' => 'LLL:EXT:demo_site/Resources/Private/Language/locallang_db.xlf:tt_content.teaser',
    'description' => 'LLL:EXT:demo_site/Resources/Private/Language/locallang_db.xlf:tt_content.teaser.description',
    'config' => [
        'type' => 'select',
        'renderType' => 'selectSingle',
        'items' => [
            ['label' => 'LLL:EXT:demo_site/Resources/Private/Language/locallang_db.xlf:tt_content.teaser.small', 'value' => 'small'],
            ['label' => 'LLL:EXT:demo_site/Resources/Private/Language/locallang_db.xlf:tt_content.teaser.large', 'value' => 'large'],
            ['label' => 'LLL:EXT:demo_site/Resources/Private/Language/locallang_db.xlf:tt_content.teaser.wide', 'value' => 'wide'],
        ],
    ],
];
