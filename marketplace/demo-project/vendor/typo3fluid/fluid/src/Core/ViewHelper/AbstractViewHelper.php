<?php

// Stub of typo3fluid/fluid for the demo project
namespace TYPO3Fluid\Fluid\Core\ViewHelper;

abstract class AbstractViewHelper
{
    protected array $arguments = [];

    public function initializeArguments(): void
    {
    }

    protected function registerArgument(string $name, string $type, string $description, bool $required = false, mixed $defaultValue = null): static
    {
        return $this;
    }
}
