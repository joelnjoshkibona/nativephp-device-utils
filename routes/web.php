<?php

use Blutrixx\DeviceUtils\Http\Controllers\DeviceUtilsController;
use Illuminate\Support\Facades\Route;

/*
| Registered under the 'web' middleware group explicitly (not implicitly,
| since these routes are loaded by this package's own ServiceProvider, not
| by the consuming app's routes/web.php) -- matches the middleware every
| consuming app already applied to these exact paths before this package
| owned them, so behavior doesn't change on upgrade.
*/
Route::middleware('web')->group(function () {
    Route::post('/device/scan', [DeviceUtilsController::class, 'scan']);
    Route::post('/device/camera/warm', [DeviceUtilsController::class, 'warmCamera']);
    Route::post('/device/photo', [DeviceUtilsController::class, 'photo']);
    Route::post('/device/photo/read', [DeviceUtilsController::class, 'readPhoto']);
    Route::post('/device/photo/pending', [DeviceUtilsController::class, 'pendingPhotos']);
    Route::post('/device/photo/take', [DeviceUtilsController::class, 'takePhoto']);
    Route::post('/device/photo/ack', [DeviceUtilsController::class, 'ackPhoto']);
    Route::post('/device/request-permissions', [DeviceUtilsController::class, 'requestPermissions']);
    Route::post('/device/file/pending', [DeviceUtilsController::class, 'filePending']);
    Route::post('/device/file/ack', [DeviceUtilsController::class, 'fileAck']);
    Route::post('/device/voice/{action}', [DeviceUtilsController::class, 'voice'])->whereIn('action', ['start', 'stop', 'cancel', 'level']);
    Route::post('/device/keyboard', [DeviceUtilsController::class, 'keyboard']);
    Route::post('/device/back-intercept', [DeviceUtilsController::class, 'backIntercept']);
});
