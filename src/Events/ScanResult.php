<?php

namespace Blutrixx\DeviceUtils\Events;

class ScanResult
{
    public function __construct(
        public readonly string $value,
        public readonly string $format,
    ) {}
}
