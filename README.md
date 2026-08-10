# Blutrixx Device Utils

A [NativePHP Mobile](https://nativephp.com) plugin exposing device-level primitives that don't belong to any one app: safe-area insets, the system file picker, app-private storage, runtime permission requests, and a camera overlay for photo capture or continuous barcode/QR scanning.

Composer package: `blutrixx/nativephp-device-utils`
Repo: `joelnjoshkibona/nativephp-device-utils`
Current release: `v1.0.0`

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

A minimal example of the JS-side listener pattern (this lives in your app, not in this package):

```ts
// resources/js/composables/useDeviceUtils.ts
import axios from 'axios'

function waitForEvent(eventClass: string, timeoutMs = 30_000): Promise<any> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      document.removeEventListener('native-event', handler)
      reject(new Error(`Timed out waiting for ${eventClass}`))
    }, timeoutMs)

    function handler(e: Event) {
      const detail = (e as CustomEvent).detail
      if (!detail?.event?.endsWith(eventClass)) return
      clearTimeout(timer)
      document.removeEventListener('native-event', handler)
      resolve(detail.data)
    }
    document.addEventListener('native-event', handler)
  })
}

export async function pickFile(mimeType = 'application/pdf') {
  const resultPromise = Promise.race([
    waitForEvent('FileSelected'),
    waitForEvent('FilePickCancelled').then(() => { throw new Error('cancelled') }),
  ])
  await axios.post('/device/pick-file', { mimeType })
  return resultPromise
}
```

(For `requestPermissions`, generate a `crypto.randomUUID()` client-side, send it as `id`, and match it against the `id` field on the incoming `PermissionsResult` event — the native side supports multiple concurrent permission requests this way. `pickFile` and `SmartCamera` calls don't take an `id`; only one file-pick or one camera overlay can be open at a time.)

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
| `open(string $mode = 'scan', string $quality = 'high')` | **Async** | `{launched: true}` | `mode: 'scan'` continuously decodes barcodes/QR and fires `ScanResult` per read; `mode: 'photo'` shows a shutter button and fires `PhotoCaptured` on capture. Either mode fires `CaptureCancelled` if the user backs out. `quality` (`'high'\|'medium'\|'low'`) only affects `'photo'` mode's JPEG output. |
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

## Quick start: scan a barcode

```php
// routes/api.php
Route::post('/device/scan/open', fn () => \Blutrixx\DeviceUtils\Facades\SmartCamera::open(mode: 'scan'));
```

```ts
// Vue
async function scanBarcode() {
  const result = waitForEvent('ScanResult') // see the listener pattern above
  await axios.post('/device/scan/open')
  const { value, format } = await result
  console.log(`Scanned ${format}: ${value}`)
}
```
