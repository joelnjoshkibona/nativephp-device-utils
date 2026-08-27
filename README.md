# Blutrixx Device Utils

A [NativePHP Mobile](https://nativephp.com) plugin exposing device-level primitives that don't belong to any one app: safe-area insets, the system file picker, app-private storage, runtime permission requests, and a camera overlay for photo capture or continuous barcode/QR scanning.

Composer package: `blutrixx/nativephp-device-utils`
Repo: `joelnjoshkibona/nativephp-device-utils`
Current release: `v1.2.0`

## Requirements

- PHP ^8.1
- A Laravel app running under `nativephp/mobile`
- Android: adds the `android.permission.CAMERA` permission and the `com.google.mlkit:barcode-scanning:17.2.0` dependency to your app's build. Camera hardware is declared `required: false` — the plugin degrades gracefully on devices without one, but `SmartCamera` calls will fail on those devices.

## Installation

This package isn't on Packagist yet. Two ways to pull it in:

**As a git submodule (how this repo itself consumes it):**

```bash
git submodule add https://github.com/joelnjoshkibona/nativephp-device-utils.git packages/nativephp-device-utils
```

```json
// composer.json
{
    "repositories": [
        {"type": "path", "url": "packages/nativephp-device-utils"}
    ],
    "require": {
        "blutrixx/nativephp-device-utils": "@dev"
    }
}
```

**Without a submodule, pointing straight at GitHub:**

```json
{
    "repositories": [
        {"type": "vcs", "url": "https://github.com/joelnjoshkibona/nativephp-device-utils"}
    ],
    "require": {
        "blutrixx/nativephp-device-utils": "^1.0"
    }
}
```

Laravel auto-discovers `DeviceUtilsServiceProvider` — no manual registration needed.

## How the bridge works

Every call goes `Vue → your Laravel route → this package's facade → nativephp_call() → native Android code`. Two shapes:

- **Synchronous** (`getInsets`, `copyToStorage`): the native side does the work and returns a result immediately — your Laravel controller can just `return` it.
- **Asynchronous** (`pickFile`, `requestPermissions`, `SmartCamera::open`): the call returns `{launched: true}` right away because the native side is opening a picker/overlay/dialog the user has to interact with. The *actual* result arrives later as a `native-event` DOM `CustomEvent` fired directly into your WebView — your frontend needs a listener for it, correlated where noted below.

This package ships the canonical JS-side listener composables for `requestPermissions`, `SmartCamera::open('scan')`, and `SmartCamera::open('photo'|'gallery')` directly, under `resources/js/` — you don't need to hand-write the event-correlation logic yourself, and every consuming app stays on the same, race-free implementation instead of drifting apart. (`pickFile`/`copyToStorage` don't have shipped composables yet — the pattern below is a template for wiring those, or any future async bridge call, yourself.)

**Wire up the alias** (Vite resolves `vendor/` paths fine — no build step or publish command needed; `composer update` alone keeps consuming apps current):

```ts
// vite.config.ts
import path from 'node:path'

export default defineConfig({
  resolve: {
    alias: {
      '@blutrixx/device-utils': path.resolve(__dirname, './vendor/blutrixx/nativephp-device-utils/resources/js'),
    },
  },
})
```

```ts
// your own resources/js/composables/useScanner.ts — a thin re-export, so
// existing imports from '@/composables/useScanner' keep working unchanged
export * from '@blutrixx/device-utils/useScanner'
```

```ts
import { useScanner } from '@/composables/useScanner'

const { scan } = useScanner()
const { data, format } = await scan()
```

**The one rule every one of these composables follows, and yours should too if you add another**: register the pending Promise in its correlation map *before* firing the `axios.post(...)` that starts the native call, not after `await`-ing its response. The native side can dispatch its result event in single-digit milliseconds — faster than the HTTP round-trip through the WebView resolves — and a listener that isn't registered yet silently drops the event. Confirmed live: this exact ordering bug made every `scan()`/`capturePhoto()` call eat the full 30-second `requestPermissions()` timeout before proceeding anyway, even when permission was already granted.

For `requestPermissions` specifically: generate a `crypto.randomUUID()` client-side, send it as `id`, and match it against the `id` field on the incoming `PermissionsResult` event — the native side supports multiple concurrent permission requests this way (already handled inside `useDevicePermissions`). `pickFile` and `SmartCamera` calls don't take an `id`; only one file-pick or one camera overlay can be open at a time.

## API reference

### `Blutrixx\DeviceUtils\Facades\DeviceUtils`

| Method | Sync? | Returns | Notes |
|---|---|---|---|
| `getInsets()` | Sync | `array` (status bar / nav bar heights in dp) | |
| `pickFile(string $mimeType = 'application/pdf')` | **Async** | `{launched: true}` | Fires `FileSelected` or `FilePickCancelled` |
| `copyToStorage(string $uri, string $filename, string $subfolder = 'books')` | Sync | `{path, filename, size}` | Copies a SAF content URI into app-private storage |
| `requestPermissions(array $permissions, ?string $id = null)` | **Async** | `{launched: true}` | Fires `PermissionsResult`, correlated by `$id` |

```php
use Blutrixx\DeviceUtils\Facades\DeviceUtils;

DeviceUtils::getInsets();
DeviceUtils::pickFile('application/pdf');
DeviceUtils::copyToStorage($uri, 'invoice.pdf', 'documents');
DeviceUtils::requestPermissions(['android.permission.CAMERA'], id: $requestId);
```

### `Blutrixx\DeviceUtils\Facades\SmartCamera`

| Method | Sync? | Returns | Notes |
|---|---|---|---|
| `open(string $mode = 'scan', string $quality = 'high', bool $multiple = false, bool $autoClose = true)` | **Async** | `{launched: true}` | `mode: 'scan'` decodes barcodes/QR and fires `ScanResult` per read (or `ScanResults`, plural, if `$multiple`, once the user taps Done); `mode: 'photo'` shows a shutter button and fires `PhotoCaptured` on capture. Either mode fires `CaptureCancelled` if the user backs out. `quality` (`'high'\|'medium'\|'low'`) only affects `'photo'` mode's JPEG output. `$multiple`/`$autoClose` apply to `'scan'` mode only — see `ScanOptions` in `useScanner.ts`. |
| `warm()` | **Async**, fire-and-forget | `{warming: true}` | Pre-binds CameraX in the background so a later `open()` shows its overlay faster — call once when a page that might scan/capture mounts. Fires no event; nothing to listen for. |
| `close()` | Sync | `array` | Dismisses the overlay if one is open |

```php
use Blutrixx\DeviceUtils\Facades\SmartCamera;

SmartCamera::open(mode: 'scan');           // barcode/QR scanning
SmartCamera::open(mode: 'photo', quality: 'medium');
SmartCamera::close();
```

## Events

All under `Blutrixx\DeviceUtils\Events\*`:

| Event | Fired by | Payload |
|---|---|---|
| `FileSelected` | `pickFile()` | file details from the SAF picker |
| `FilePickCancelled` | `pickFile()` | — (user backed out) |
| `PermissionsResult` | `requestPermissions()` | grant results, correlated by `id` |
| `PhotoCaptured` | `SmartCamera::open(mode: 'photo')` | `{path, base64, width, height, size}` |
| `CaptureCancelled` | `SmartCamera::open(...)` | — (user closed the overlay) |
| `ScanResult` | `SmartCamera::open(mode: 'scan')` | `{value, format}` — `format` is one of `QR_CODE`, `EAN_13`, `EAN_8`, `UPC_A`, `UPC_E`, `CODE_128`, `CODE_39`, `DATA_MATRIX` |
| `ScanResults` | `SmartCamera::open(mode: 'scan', multiple: true)`, once the user taps Done | `{results: [{value, format}, ...]}` |

## Quick start: scan a barcode

```php
// routes/web.php or routes/api.php
Route::post('/device/scan', function (Request $request) {
    \Blutrixx\DeviceUtils\Facades\SmartCamera::open(
        'scan', 'high', $request->boolean('multiple'), $request->boolean('autoClose', true)
    );
    return response()->json(['started' => true]);
});
```

```ts
// Vue -- useScanner() is shipped by this package, see "Wire up the alias" above
import { useScanner } from '@/composables/useScanner'

const { scan, scanning } = useScanner()
const { data, format } = await scan()
console.log(`Scanned ${format}: ${data}`)
```
