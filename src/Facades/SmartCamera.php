<?php

namespace Blutrixx\DeviceUtils\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static array open(string $mode = 'scan', string $quality = 'high')
 * @method static array close()
 *
 * @see \Blutrixx\DeviceUtils\SmartCamera
 */
class SmartCamera extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return \Blutrixx\DeviceUtils\SmartCamera::class;
    }
}
