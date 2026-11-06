<?php

use TYPO3\CMS\Core\Imaging\IconProvider\SvgIconProvider;

return [
    'demo-ticket' => [
        'provider' => SvgIconProvider::class,
        'source' => 'EXT:demo_site/Resources/Public/Icons/ticket.svg',
    ],
    'demo-map' => [
        'provider' => SvgIconProvider::class,
        'source' => 'EXT:demo_site/Resources/Public/Icons/map.svg',
    ],
];
