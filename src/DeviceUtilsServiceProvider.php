<?php

namespace Blutrixx\DeviceUtils;

use Illuminate\Contracts\Debug\ExceptionHandler;
use Illuminate\Support\ServiceProvider;
use Throwable;

class DeviceUtilsServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->app->singleton(DeviceUtils::class, fn () => new DeviceUtils());
        $this->app->alias(DeviceUtils::class, 'nativephp.device-utils');

        $this->app->singleton(AppLog::class, fn () => new AppLog());
        $this->app->alias(AppLog::class, 'nativephp.app-log');

        $this->app->singleton(SmartCamera::class, fn () => new SmartCamera());
        $this->app->alias(SmartCamera::class, 'nativephp.smart-camera');
    }

    public function boot(): void
    {
        $this->loadRoutesFrom(__DIR__.'/../routes/web.php');

        // every exception Laravel reports also goes into the app's log (class, place, a short message without SQL); the normal report goes on as before
        $handler = $this->app->make(ExceptionHandler::class);
        if (method_exists($handler, 'reportable')) {
            $handler->reportable(function (Throwable $e) {
                try {
                    $this->app->make(AppLog::class)->exception($e);
                } catch (Throwable) {
                    // the log must never be the cause of another error
                }
            });
        }
    }
}
