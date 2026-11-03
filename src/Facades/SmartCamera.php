<?php

namespace Blutrixx\DeviceUtils\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static array open(string $mode = 'scan', string $quality = 'high', bool $multiple = false, bool $autoClose = true)
 * @method static array close()
 * @method static array warm()
 * @method static array pending()
 * @method static array take(string $id)
 * @method static array ack(string $id)
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
