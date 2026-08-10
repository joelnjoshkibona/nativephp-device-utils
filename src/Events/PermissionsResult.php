<?php

namespace Blutrixx\DeviceUtils\Events;

class PermissionsResult
{
    /**
     * @param  array<string, bool>  $results  Map of Android permission string => granted
     */
    public function __construct(
        public readonly array $results,
        public readonly ?string $id = null,
    ) {}
}
