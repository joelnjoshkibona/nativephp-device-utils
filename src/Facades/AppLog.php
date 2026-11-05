<?php

namespace Blutrixx\DeviceUtils\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static void write(string $level, string $tag, string $message)
 * @method static void info(string $tag, string $message)
 * @method static void warn(string $tag, string $message)
 * @method static void error(string $tag, string $message)
 * @method static void exception(\Throwable $e, string $tag = 'php')
 * @method static void writeMany(array $entries)
 * @method static array read(int $limit = 200, string $level = 'D')
 * @method static void clear()
 * @method static array crash()
 * @method static bool setDetail(bool $on)
 * @method static array export(bool $share = true, ?string $folder = null)
 *
 * @see \Blutrixx\DeviceUtils\AppLog
 */
class AppLog extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return \Blutrixx\DeviceUtils\AppLog::class;
    }
}
