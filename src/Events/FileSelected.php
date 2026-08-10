<?php

namespace Blutrixx\DeviceUtils\Events;

class FileSelected
{
    public function __construct(
        public readonly string $uri,
        public readonly string $filename,
        public readonly int $size,
        public readonly string $mimeType,
    ) {}
}
