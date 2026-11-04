<?php

namespace Blutrixx\DeviceUtils\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static array getInsets()
 * @method static array pickFile(string $mimeType = 'application/pdf')
 * @method static array copyToStorage(string $uri, string $filename, string $subfolder = 'books')
 * @method static array requestPermissions(array $permissions, ?string $id = null)
 *
 * @method static array setBackIntercept(bool $enabled)
 * @method static array voiceStart()
 * @method static array voiceStop()
 * @method static array voiceCancel()
 * @method static float voiceLevel()
 * @method static array openFile(string $path, string $mime, string $name, string $folder = 'Hodie')
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
