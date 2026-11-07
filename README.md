# Blutrixx Device Utils

A [NativePHP Mobile](https://nativephp.com) plugin exposing device-level primitives that don't belong to any one app: safe-area insets, the system file picker, app-private storage, runtime permission requests, and a camera overlay for photo capture or continuous barcode/QR scanning.

The routes, controller, and JS composables are all part of this package too (see below) — a consuming app needs zero PHP or JS code of its own to use `scan`, `photo`, `warm`, or `requestPermissions`. `getInsets`/`pickFile`/`copyToStorage` don't have a shipped route yet — call the facade directly from a route of your own (see the API reference).

Composer package: `blutrixx/nativephp-device-utils`
Repo: `joelnjoshkibona/nativephp-device-utils`
Current release: `v1.3.0`

**Upgrading an existing consumer from before v1.3.0?** Delete your app's own `NativeDeviceController` (or equivalent) and its `/device/scan`, `/device/camera/warm`, `/device/photo`, `/device/photo/read`, `/device/request-permissions` route lines — this package now registers those same paths itself. Leaving both in place means two controllers answer the same route; Laravel resolves to whichever registers first, which is confusing to debug and not a state you want to be in.

## Photos that survive the app being killed (v1.5.0)

On a phone with little memory Android can kill the app while the system picker (gallery) is in front. The picked photo then reaches a page that has not loaded yet, and the `PhotoCaptured` event is lost. Now every photo, from the camera or the gallery, is saved natively (`files/smart_camera_pending/`) before the event is sent, and `PhotoCaptured` carries its `id`. A photo nobody confirms is deleted after 48 hours.

* `capturePhoto()` acknowledges the photo for you once the page has received it. Nothing to change in an existing app.
* New: call `recoverPhotos()` when a page that uses the camera mounts. It returns the photos that never arrived (`{ base64, mimeType, id, width, height }[]`) and you save them like a normal capture; call `ackPhoto(id)` after saving each one.
* PHP: `SmartCamera::pending()`, `SmartCamera::take($id)`, `SmartCamera::ack($id)`; routes `POST /device/photo/pending`, `/device/photo/take`, `/device/photo/ack`.
* Also fixed: when Android restored the camera fragment after killing the app, it opened the gallery a second time. It now relies on the result Android already holds.
* Also new: `SmartCamera.Open` hides the soft keyboard and blurs the page before the camera or gallery appears.

Tested on a Telpo M1K (Android 12): gallery opened, app process killed with the picker open, photo picked, app restarted. The photo (73 KB) was found in the pending folder, collected by the page on load, shown in the editor, acknowledged, and the pending folder was empty. A normal pick without a kill arrived live and was acknowledged too. No second picker opened.

## Voice notes, files and camera errors (v1.7.0)

* `DeviceUtils::voiceStart()`, `voiceStop()`, `voiceCancel()`, `voiceLevel()`: record a voice note with the microphone into the app's private `hodie_import` folder (AAC in `.m4a`, mono, 64 kbit/s). `voiceStart()` answers the error code `NO_PERMISSION` until `RECORD_AUDIO` has been granted (ask with `requestPermissions()` first). `voiceStop()` returns `{path, filename, seconds, size}`. Routes `POST /device/voice/{start,stop,cancel,level}`. Android lets only an app in the foreground use the microphone, so a recording ends when the app is left.
* `DeviceUtils::openFile($path, $mime, $name, $folder = 'Hodie')`: open a file from the app's private storage with the app that handles its type. Other apps cannot read the private folder, so the file is copied once into `Downloads/<folder>` (MediaStore, no storage permission) and opened from there. Needs Android 10 or newer; errors `NO_APP`, `UNSUPPORTED`, `BAD_PATH`.

* File picker: the picked document is copied into the app's `pending_files` folder the moment the picker returns, and `FileSelected` carries `{id, path, filename, mimeType, size}` (the real type). If the page was not there to receive it, `DeviceUtils::filePending()` lists it (`POST /device/file/pending`) and `fileAck($id)` (`/device/file/ack`) deletes the copy once the app has stored its own; copies nobody confirms go after 48 hours.

Tested on an Android 16 emulator and an Infinix (Android 10): permission dialog, a 12 second recording (101 KB), a PDF opened through the system "Open with" list.

## Logs (v1.8.0)

The app's own log, kept on the phone and exportable, so a crash or an odd behaviour can be followed up afterwards. A text file in private storage (`files/applog/app.log`, three files of 400 KB) written by native code, so it works when PHP or the web view is dead. A content provider starts it when the process starts and installs a crash handler, so crashes (also early ones) are recorded and the next start can say so.

* **PHP:** `AppLog::info('places', 'tracking on')`, `warn`, `error`, `exception($e)`, `read($limit, $level)`, `clear()`, `crash()` (the crash that ended the last run, once), `setDetail(bool)` and `export($share = true, $folder = null)`. Every exception Laravel reports is also written (class, file and line, a message of at most 200 characters with any SQL removed). Without the bridge (a development server, tests) the same format is written under `storage/app/applog`.
* **JS:** `useAppLog()` (`resources/js/useAppLog.ts`), or plain `POST /device/log`, `/device/log/read`, `/clear`, `/crash`, `/detail`, `/export`. It records JavaScript errors and unhandled rejections by itself.
* **Export:** saves a text file in Downloads/<app name> (MediaStore, no permission) and opens Android's share sheet. Nothing leaves the phone unless the person chooses where it goes.
* **Other plugins** can write with `AppLog.write(level, tag, message)` (a static Kotlin method; by reflection if they do not want a dependency). `AppLog.detail()` says whether the person switched on detailed place logging.
* **Rule:** never write personal content (note text, amounts, names, PINs, coordinates). Times, counts, error codes and names of processes only.

## Phone status (v1.9.0)

`DeviceUtils::getStatus()` (`POST /device/status`) is one reading of the phone: battery percent and charging (and how: usb, ac, wireless), network (`wifi`, `mobile`, `ethernet`, `none`), mobile generation (`2G` to `5G`, left out when Android does not say: from Android 11 it needs a permission this plugin does not take), signal level 0 to 4 (the wifi joined, or the mobile radio), power saving, airplane mode, Android version and API level, model, and free and total storage. **Nothing that identifies the person or the place**: no wifi name, no phone number, no SIM or cell identifiers. A part the phone refuses is left out, never an error. Adds the permissions `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` (both granted at install, no prompt).

`com.blutrixx.device.utils.DeviceStatus.snapshot(context)` is the short form `{"b":85,"c":1,"n":"w","g":"4G","s":3,"p":0}` (battery, charging, network w/m/e/x, generation, signal, power saving) that another plugin can attach to its own records by reflection; `blutrixx/nativephp-geo-location` 1.2.0 attaches it to each location fix, so a reading is kept with every fix, with the app closed too.

## Area names (v1.10.0)

`DeviceUtils::geocode($lat, $lng, $lang = null)` (`POST /device/geocode`) asks Android's own Geocoder for the name of the area at a position: `street`, `area` (Android's sub-locality: a neighbourhood or ward where the data has one), `town`, `district`, `region`, `country`, `countryCode`, `feature`. Missing parts are left out; nothing found is `{found: false}`; a failure says why in `error` (`NO_GEOCODER`, `NETWORK`, `TIMEOUT`, `SERVICE`, `BAD_POSITION`). No key or account. **It needs the internet and the position leaves the phone** (Google's service on most phones), so name only what the person agreed to, and keep the answer instead of asking twice. It waits up to 10 seconds: call it from PHP, not in a loop.

## The on-screen keyboard (v1.7.0)

On Android 10 the keyboard opens on top of the web view without resizing it, so it hides the lower part of a sheet or a form and the field you are typing in. `DeviceUtils::getKeyboard()` (`POST /device/keyboard`) says how much of the screen it covers, `{visible, height}` in dp. `useKeyboard()` (`resources/js/useKeyboard.ts`) uses it for you: it sets the CSS variable `--kb` on `<html>` (px, `0px` while the keyboard is down) and `--kb-vh` (the height left above it), calls you back on every change, and scrolls the field being typed in to the middle of the visible part. Use the variable: `.sheet { bottom: var(--kb, 0px) }`, `.page { padding-bottom: var(--kb, 0px) }`. Where Android cannot answer it falls back to an estimate and says so (`exact: false`).

## Requirements

- PHP ^8.1
- A Laravel app running under `nativephp/mobile` ^3.3 (enforced by `composer.json`; 4.x is not supported yet)
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

Laravel auto-discovers `DeviceUtilsServiceProvider` — no manual registration needed. Its `boot()` loads this package's own `routes/web.php` (registered under the `web` middleware group), which wires `/device/scan`, `/device/camera/warm`, `/device/photo`, `/device/photo/read`, and `/device/request-permissions` straight to `Http\Controllers\DeviceUtilsController` — nothing to add to your own `routes/web.php`.

## How the bridge works

Every call goes `Vue → this package's own route → this package's facade → nativephp_call() → native Android code`. Two shapes:

- **Synchronous** (`getInsets`, `copyToStorage`): the native side does the work and returns a result immediately — a route can just `return` it. (These don't have a shipped route yet — call the facade directly from a route of your own, e.g. `Route::get('/device/insets', fn () => DeviceUtils::getInsets());`.)
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

**If you consume this package via a composer `"path"` repository** (local development — this shell's own `composer.json` does this), composer installs it as a *symlink* into `vendor/`, not a copy. Vite/Rollup then resolves this package's own imports (`axios`, inside `useScanner.ts` etc.) against the symlink's real target directory instead of your app's `node_modules`, and the build fails with `Rollup failed to resolve import "axios"`. Fix: add `resolve.preserveSymlinks: true` to `vite.config.ts`. A real install (this package pulled via its GitHub VCS repo/tag, the normal case for a generated project) is a plain copy and needs no such flag.

**The one rule every one of these composables follows, and yours should too if you add another**: register the pending Promise in its correlation map *before* firing the `axios.post(...)` that starts the native call, not after `await`-ing its response. The native side can dispatch its result event in single-digit milliseconds — faster than the HTTP round-trip through the WebView resolves — and a listener that isn't registered yet silently drops the event. Confirmed live: this exact ordering bug made every `scan()`/`capturePhoto()` call eat the full 30-second `requestPermissions()` timeout before proceeding anyway, even when permission was already granted.

For `requestPermissions` specifically: generate a `crypto.randomUUID()` client-side, send it as `id`, and match it against the `id` field on the incoming `PermissionsResult` event — the native side supports multiple concurrent permission requests this way (already handled inside `useDevicePermissions`). `pickFile` and `SmartCamera` calls don't take an `id`; only one file-pick or one camera overlay can be open at a time.

## API reference

### `Blutrixx\DeviceUtils\Facades\DeviceUtils`

| Method | Sync? | Returns | Notes |
|---|---|---|---|
| `getInsets()` | Sync | `array` (status bar / nav bar heights in dp) | |
| `geocode(float $lat, float $lng, ?string $lang = null)` | Sync (up to 10 s) | `{found, street?, area?, town?, district?, region?, country?, countryCode?, feature?, error?}` | The area at a position through Android's Geocoder (v1.10.0); needs the internet, the position goes to the service; `POST /device/geocode` |
| `getStatus()` | Sync | `{battery, charging, plug, batteryTemp, network, generation, signal, powerSave, airplane, android, sdk, model, manufacturer, storageFree, storageTotal}` | One reading of the phone's state (v1.9.0); `POST /device/status`; see Phone status below |
| `getKeyboard()` | Sync | `{visible, height}` (dp) | How much of the screen the on-screen keyboard covers; see `useKeyboard` below |
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
| `CaptureCancelled` | `SmartCamera::open(...)` | — (user closed the overlay, or the camera could not be used: see `CaptureFailed`) |
| `CaptureFailed` | `SmartCamera::open(...)` | `{code, reason}` — the camera opened and Android then refused it (`camera_disabled`, `camera_in_use`, `max_cameras_in_use`, `camera_fatal`, `do_not_disturb`, `stream_config`, `bind_failed`, `camera_error`). Sent once, just before `CaptureCancelled`, so a listener that only handles `CaptureCancelled` still stops waiting. The overlay closes itself instead of staying on a black screen. |
| `ScanResult` | `SmartCamera::open(mode: 'scan')` | `{value, format}` — `format` is one of `QR_CODE`, `EAN_13`, `EAN_8`, `UPC_A`, `UPC_E`, `CODE_128`, `CODE_39`, `DATA_MATRIX` |
| `ScanResults` | `SmartCamera::open(mode: 'scan', multiple: true)`, once the user taps Done | `{results: [{value, format}, ...]}` |

## Quick start: scan a barcode

No PHP to write — `/device/scan` already exists once this package is installed. Just wire the alias (see "Wire up the alias" above), add the one-line re-export composable file, and call it:

```ts
// Vue -- useScanner() is shipped by this package, see "Wire up the alias" above
import { useScanner } from '@/composables/useScanner'

const { scan, scanning } = useScanner()
const { data, format } = await scan()
console.log(`Scanned ${format}: ${data}`)
```
