<?php

declare(strict_types=1);

namespace Acme\DemoSite\DataProcessing;

use TYPO3\CMS\Frontend\ContentObject\ContentObjectRenderer;
use TYPO3\CMS\Frontend\ContentObject\DataProcessorInterface;

final class TeaserProcessor implements DataProcessorInterface
{
    public function process(ContentObjectRenderer $cObj, array $contentObjectConfiguration, array $processorConfiguration, array $processedData): array
    {
        $processedData['teasers'] = [
            ['title' => 'Harbour tour', 'link' => 12],
            ['title' => 'Museum night', 'link' => 14],
        ];
        return $processedData;
    }
}
