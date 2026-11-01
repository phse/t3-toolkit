<?php

declare(strict_types=1);

namespace Acme\DemoSite\EventListener;

use TYPO3\CMS\Frontend\Event\AfterCacheableContentIsGeneratedEvent;

final class AddTeaserData
{
    public function __invoke(AfterCacheableContentIsGeneratedEvent $event): void
    {
    }
}
