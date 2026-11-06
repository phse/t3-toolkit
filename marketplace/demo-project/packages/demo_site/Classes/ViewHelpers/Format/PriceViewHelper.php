<?php

declare(strict_types=1);

namespace Acme\DemoSite\ViewHelpers\Format;

use TYPO3Fluid\Fluid\Core\ViewHelper\AbstractViewHelper;

/**
 * Formats an amount as a price with two decimals and a currency sign.
 *
 * <demo:format.price amount="19.9" />
 */
final class PriceViewHelper extends AbstractViewHelper
{
    public function initializeArguments(): void
    {
        $this->registerArgument('amount', 'float', 'Amount in euro', true);
        $this->registerArgument('currency', 'string', 'Currency sign', false, '€');
    }

    public function render(): string
    {
        return number_format((float)$this->arguments['amount'], 2, ',', '.') . ' ' . $this->arguments['currency'];
    }
}
