<?php

namespace Blutrixx\DeviceUtils\Http\Controllers;

use Blutrixx\DeviceUtils\Facades\DeviceUtils;
use Blutrixx\DeviceUtils\Facades\SmartCamera;
use finfo;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Routing\Controller;

/**
 * Bridge controller for the native QR/barcode scanner, camera, and runtime
 * permissions -- registers its own routes (see ../../routes/web.php, loaded
 * by DeviceUtilsServiceProvider::boot()), so a consuming app needs zero
 * controller or route code of its own to use this package.
 *
 * Flow: Vue frontend (see resources/js/useScanner.ts, useCamera.ts,
 * usePermissions.ts) → these routes → SmartCamera/DeviceUtils facades →
 * nativephp_call() → Kotlin/Swift native UI. Scan/photo/permission results
 * are NOT returned by these endpoints -- they arrive later as `native-event`
 * DOM CustomEvents dispatched directly into the WebView by native code; the
 * shipped composables already handle the correlation.
 *
 * Not nativephp/mobile's own Scanner/Camera facades -- nativephp/mobile
 * ships no Android implementation for ANY Device.* or Scanner.* bridge
 * function (confirmed live for both Device.GetInfo and Scanner.Scan:
 * "Function ... not found").
 */
class DeviceUtilsController extends Controller
{
    /**
     * Start the native QR/barcode scanner (SmartCamera's 'scan' mode:
     * continuous ML Kit barcode detection, fires as soon as one code is
     * read). Only launches the scanner UI -- the result arrives later via
     * the ScanResult (or CaptureCancelled) event; no `id` correlation
     * needed, same "only one capture in flight" rule as photo().
     */
    public function scan(Request $request): JsonResponse
    {
        if (! function_exists('nativephp_call')) {
            return response()->json([
                'started' => false,
                'error'   => 'Native bridge not available. Run this inside the mobile app.',
            ], 503);
        }

        SmartCamera::open(
            'scan',
            'high',
            $request->boolean('multiple'),
            $request->boolean('autoClose', true)
        );

        return response()->json(['started' => true]);
    }

    /**
     * Pre-warm CameraX in the background (no UI shown) so a later scan()/
     * photo() opens the camera overlay faster. Fire-and-forget: the actual
     * SmartCamera.Open call is near-instant; the real cold-start cost lives
     * in CameraX's own ProcessCameraProvider.getInstance() binding, which
     * this kicks off ahead of time. Requires SmartCamera::warm() (v1.1.0+).
     */
    public function warmCamera(): JsonResponse
    {
        if (! function_exists('nativephp_call')) {
            return response()->json(['warming' => false], 503);
        }

        SmartCamera::warm();

        return response()->json(['warming' => true]);
    }

    /**
     * Start native photo capture, or launch the system gallery/image picker
     * instead if the client posts `source=gallery`.
     *
     * Only launches the camera/picker UI. The result arrives later via the
     * PhotoCaptured (or CaptureCancelled) event -- both sources funnel
     * through the same SmartCamera native fragment and fire the identical
     * event shape. Unlike scan()/requestPermissions(), there is no `id`
     * correlation -- the native side only ever allows one capture in flight
     * at a time.
     */
    public function photo(Request $request): JsonResponse
    {
        if (! function_exists('nativephp_call')) {
            return response()->json([
                'started' => false,
                'error'   => 'Native bridge not available. Run this inside the mobile app.',
            ], 503);
        }

        $mode = $request->string('source')->toString() === 'gallery' ? 'gallery' : 'photo';
        SmartCamera::open($mode, 'high');

        return response()->json(['started' => true]);
    }

    /**
     * Request one or more dangerous Android runtime permissions.
     *
     * Only launches the system permission dialog (or resolves immediately if
     * everything requested is already granted). The result arrives later via
     * the PermissionsResult event, correlated by the returned `id`.
     */
    public function requestPermissions(Request $request): JsonResponse
    {
        if (! function_exists('nativephp_call')) {
            return response()->json([
                'started' => false,
                'error'   => 'Native bridge not available. Run this inside the mobile app.',
            ], 503);
        }

        $id = $request->string('id')->toString() ?: (string) \Illuminate\Support\Str::uuid();

        $permissions = array_values(array_filter(
            (array) $request->input('permissions', []),
            fn ($permission) => is_string($permission) && $permission !== ''
        ));

        if (empty($permissions)) {
            return response()->json(['started' => false, 'error' => 'permissions is required'], 422);
        }

        DeviceUtils::requestPermissions($permissions, $id);

        return response()->json(['started' => true, 'id' => $id]);
    }

    /**
     * Read a captured photo off the device filesystem and return it as base64.
     *
     * NOT used by the shipped useCamera.ts flow -- SmartCamera's
     * PhotoCaptured event already carries the base64 JPEG directly, no file
     * path round-trip needed. Left in place (unused, harmless) for an app
     * that wants the nativephp/mobile Camera-style path-based flow instead.
     *
     * The SPA cannot access the native filesystem directly -- it receives
     * the file `path` from a native event payload and forwards it here.
     *
     * SECURITY: `$path` is fully untrusted. PHP never observes a native
     * event directly (it's injected into the WebView as a DOM CustomEvent)
     * -- it only sees whatever string the webview later posts back. A
     * malicious or compromised webview context could request any path
     * readable by the app process (.env, database/database.sqlite,
     * storage/framework/*, etc). isSafePhotoPath() anchors the resolved path
     * to NativePHP's own capture/temp sandbox and additionally requires the
     * content to actually be an image.
     */
    public function readPhoto(Request $request): JsonResponse
    {
        $path = $request->string('path')->toString();

        if ($path === '' || ! $this->isSafePhotoPath($path)) {
            return response()->json(['error' => 'Photo file not found'], 404);
        }

        // Re-resolve for the actual read: isSafePhotoPath() already proved
        // this is safe, but we read via the realpath()'d value, never the
        // raw request string.
        $realPath = realpath($path);

        $contents = @file_get_contents($realPath);
        if ($contents === false) {
            return response()->json(['error' => 'Unable to read photo file'], 500);
        }

        $mimeType = $request->string('mimeType')->toString();
        if ($mimeType === '') {
            $mimeType = (function_exists('mime_content_type') ? mime_content_type($realPath) : false) ?: 'image/jpeg';
        }

        return response()->json([
            'base64'   => base64_encode($contents),
            'mimeType' => $mimeType,
        ]);
    }

    /**
     * Validate that a client-supplied path is a real, readable, in-sandbox
     * image file -- never a path into the consuming app's own tree.
     *
     * Anchor directory: config('nativephp-internal.tempdir').
     *
     * Why this anchor: NativePHP itself treats this directory as the
     * device's capture/temp sandbox and wires it in from the native side --
     *   - Android: LaravelEnvironment.kt sets the env var backing this
     *     config to `context.cacheDir.absolutePath`, and the FileProvider
     *     used to hand a just-captured photo back to the app exposes that
     *     same cache dir wholesale (`<cache-path name="cache" path="." />`).
     *   - iOS: NATIVEPHP_TEMPDIR is set to `NSTemporaryDirectory()`.
     *   - NativePHP's own NativeServiceProvider::registerFilesystems() roots
     *     a `temp` filesystem disk at this exact config value, i.e.
     *     NativePHP itself treats it as the one legitimate device-local
     *     scratch/capture area.
     *
     * We anchor to that sandbox root (falling back to sys_get_temp_dir()
     * only if the NativePHP config is unset, e.g. outside the mobile shell)
     * rather than to an exact file path, and explicitly deny the consuming
     * app's own tree even if a future build ever widened the sandbox to
     * overlap it.
     */
    private function isSafePhotoPath(string $path): bool
    {
        $real = realpath($path);

        if ($real === false || ! is_file($real) || ! is_readable($real)) {
            return false;
        }

        // Hard denylist: the consuming app's tree (config/env, storage,
        // database) must never be servable here, regardless of the
        // allowlist below.
        foreach ([base_path(), storage_path(), database_path()] as $forbidden) {
            $forbiddenReal = realpath($forbidden);
            if ($forbiddenReal !== false && $this->isWithin($real, $forbiddenReal)) {
                return false;
            }
        }

        // Allowlist: must resolve inside a NativePHP-owned sandbox/temp root.
        $roots = [];
        if ($tempDir = config('nativephp-internal.tempdir')) {
            $roots[] = realpath($tempDir);
        }
        $roots[] = realpath(sys_get_temp_dir());

        $withinSandbox = false;
        foreach (array_filter($roots) as $root) {
            if ($this->isWithin($real, $root)) {
                $withinSandbox = true;
                break;
            }
        }

        if (! $withinSandbox) {
            return false;
        }

        // Content check: reasonable size cap + must genuinely be an image,
        // independent of file extension or client-supplied mimeType.
        $size = filesize($real);
        if ($size === false || $size > 25 * 1024 * 1024) {
            return false;
        }

        $finfo = new finfo(FILEINFO_MIME_TYPE);
        $mime = $finfo->file($real);

        return is_string($mime) && str_starts_with($mime, 'image/');
    }

    /**
     * Whether a resolved real path sits at or inside a resolved real root.
     */
    private function isWithin(string $realPath, string $realRoot): bool
    {
        return $realPath === $realRoot
            || str_starts_with($realPath.DIRECTORY_SEPARATOR, rtrim($realRoot, DIRECTORY_SEPARATOR).DIRECTORY_SEPARATOR);
    }
}
