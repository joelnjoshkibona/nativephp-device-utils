<?php

namespace Blutrixx\DeviceUtils\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static array getInsets()
 * @method static array pickFile(string $mimeType = 'application/pdf')
 * @method static array copyToStorage(string $uri, string $filename, string $subfolder = 'books')
 * @method static array requestPermissions(array $permissions, ?string $id = null)
 *
 * @see \NativePHP\DeviceUtils\DeviceUtils
 */
class DeviceUtils extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return \Blutrixx\DeviceUtils\DeviceUtils::class;
    }
}
