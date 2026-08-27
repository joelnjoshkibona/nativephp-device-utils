<?php

namespace Blutrixx\DeviceUtils;

use Illuminate\Support\ServiceProvider;

class DeviceUtilsServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->app->singleton(DeviceUtils::class, fn () => new DeviceUtils());
        $this->app->alias(DeviceUtils::class, 'nativephp.device-utils');

        $this->app->singleton(SmartCamera::class, fn () => new SmartCamera());
        $this->app->alias(SmartCamera::class, 'nativephp.smart-camera');
    }

    public function boot(): void
    {
        $this->loadRoutesFrom(__DIR__.'/../routes/web.php');
    }
}
