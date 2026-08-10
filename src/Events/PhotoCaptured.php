<?php

namespace Blutrixx\DeviceUtils\Events;

class PhotoCaptured
{
    public function __construct(
        public readonly string $path,
        public readonly string $base64,
        public readonly int    $width,
        public readonly int    $height,
        public readonly int    $size,
    ) {}
}
