<?php

namespace Blutrixx\DeviceUtils\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static array open(string $mode = 'scan', string $quality = 'high', bool $multiple = false, bool $autoClose = true)
 * @method static array close()
 * @method static array warm()
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
