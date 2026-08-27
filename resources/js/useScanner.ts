import { ref } from 'vue'
import axios from 'axios'
import { ensurePermissions } from './usePermissions'

export interface ScanResult {
    data: string
    format: string
}

export interface ScanOptions {
    /**
     * Barcode formats to scan for. Accepted for API compatibility but not
     * currently enforceable — SmartCamera's native 'scan' mode (ML Kit)
     * always detects all supported barcode/QR formats regardless of this.
     */
    formats?: string[]
    /**
     * Custom prompt text shown on the native scanner screen. Accepted for API
     * compatibility but not currently enforceable — SmartCamera's native
     * 'scan' mode shows a fixed on-screen hint regardless of this.
     */
    prompt?: string
    /** Superseded by scanMultiple() — kept only for API compatibility, unused. */
    continuous?: boolean
    /**
     * scan() only (ignored by scanMultiple(), which is always manual-close).
     * true (default): resolve and dismiss the scanner as soon as a code is
     * decoded. false: stop scanning but show a "Found: <value>" confirm/retry
     * UI instead of closing — this promise only resolves once the user taps
     * "Use this code" (they can also tap "Scan again" to keep looking).
     */
    autoClose?: boolean
    /** Reject if no result arrives within this many ms. Default: 60000 for scan(), 300000 for scanMultiple(). */
    timeoutMs?: number
}

/**
 * Pre-warm the native camera subsystem (CameraX) in the background so a
 * later scan()/capturePhoto() call opens the camera overlay noticeably
 * faster. Call from onMounted() on any page that might use the scanner or
 * camera — CameraX's own cold-start binding (ProcessCameraProvider.getInstance())
 * is the actual source of "camera takes a while to open", not the native
 * bridge call itself (confirmed via logcat during live device testing: both
 * SmartCamera.Open and this warm call return in well under a second). Best
 * effort only — fire-and-forget, never throws, safe to call speculatively
 * even outside the native app (silently no-ops).
 */
export async function warmCamera(): Promise<void> {
    try {
        await axios.post('/device/camera/warm')
    } catch { /* best-effort only */ }
}

const scanning = ref(false)

type PendingScan = {
    resolve: (value: ScanResult) => void
    reject: (reason: Error) => void
    timer: ReturnType<typeof setTimeout>
}

type PendingMultiScan = {
    resolve: (value: ScanResult[]) => void
    reject: (reason: Error) => void
    timer: ReturnType<typeof setTimeout>
}

// SmartCamera.Open only ever allows one capture (scan or photo) in flight at a
// time (enforced natively), and its ScanResult/ScanResults/CaptureCancelled
// events carry no `id` to correlate against — a single slot per kind is
// sufficient, no Map needed. Only one of pendingScan/pendingMultiScan is ever
// set at once, matching whichever of scan()/scanMultiple() is in flight.
// Mirrors useCamera.ts, which uses the same SmartCamera plugin for photo capture.
let pendingScan: PendingScan | null = null
let pendingMultiScan: PendingMultiScan | null = null

function settle(run: (pending: PendingScan) => void) {
    const pending = pendingScan
    if (!pending) return
    clearTimeout(pending.timer)
    pendingScan = null
    run(pending)
}

function settleMulti(run: (pending: PendingMultiScan) => void) {
    const pending = pendingMultiScan
    if (!pending) return
    clearTimeout(pending.timer)
    pendingMultiScan = null
    run(pending)
}

// ── Native event listener via DOM CustomEvent ────────────────────────────────
// NativePHP injects native events directly into the WebView as CustomEvents on
// `document` — this is the verified, authoritative delivery path (see
// useCamera.ts, which documents the same mechanism).
let listenerAttached = false
function attachListener() {
    if (listenerAttached) return
    listenerAttached = true

    document.addEventListener('native-event', (e: Event) => {
        const ce = e as CustomEvent
        const eventName = ce.detail?.event as string
        let payload = ce.detail?.payload
        if (!eventName) return

        if (typeof payload === 'string') {
            try { payload = JSON.parse(payload) } catch { /* keep as-is */ }
        }

        // Delivered event name is the full FQCN tail including the `Events\`
        // sub-namespace (verified live: 'Blutrixx\DeviceUtils\Events\PhotoCaptured').
        if (eventName.endsWith('Events\\ScanResult')) {
            settle((pending) => pending.resolve({ data: payload?.value, format: payload?.format }))
        }
        else if (eventName.endsWith('Events\\ScanResults')) {
            const results: ScanResult[] = Array.isArray(payload?.results)
                ? payload.results.map((r: any) => ({ data: r?.value, format: r?.format }))
                : []
            settleMulti((pending) => pending.resolve(results))
        }
        else if (eventName.endsWith('Events\\CaptureCancelled')) {
            settle((pending) => pending.reject(new Error('Scan cancelled')))
            settleMulti((pending) => pending.reject(new Error('Scan cancelled')))
        }
    })
}

attachListener()

/**
 * Composable for the native QR/barcode scanner via our in-house SmartCamera
 * plugin (Blutrixx\DeviceUtils\Facades\SmartCamera).
 *
 * Flow: Vue frontend → your Laravel scan() route → SmartCamera::open('scan')
 * → native ML Kit scanner overlay → ScanResult (or ScanResults, see
 * scanMultiple()) delivered as a `native-event` DOM CustomEvent.
 *
 * Not Native\Mobile\Scanner — nativephp/mobile ships no Android
 * implementation of its Scanner.Scan bridge function at all, so that facade
 * can never work on Android. Route your controller's scan endpoint through
 * SmartCamera::open('scan', ...) instead.
 *
 * Gracefully rejects (never hangs) when the native bridge isn't available,
 * when the user cancels, or when nothing is scanned before the timeout.
 */
export function useScanner() {

    async function scan(options: ScanOptions = {}): Promise<ScanResult> {
        scanning.value = true

        try {
            // Just-in-time permission prompt, right before the native scanner
            // is invoked. Best-effort only — must never block the scan attempt,
            // the native scanner still enforces the actual permission.
            await ensurePermissions(['android.permission.CAMERA']).catch(() => {})

            return await new Promise<ScanResult>((resolve, reject) => {
                const timer = setTimeout(() => {
                    pendingScan = null
                    reject(new Error('Scan timed out'))
                }, options.timeoutMs ?? 60000)

                // Register BEFORE posting, not after awaiting the response —
                // the native side can dispatch ScanResult/CaptureCancelled
                // faster than this axios round-trip resolves (see the
                // equivalent, actually-observed race in usePermissions.ts's
                // requestPermissions()). Registering first closes that race
                // regardless of which settles first.
                pendingScan = { resolve, reject, timer }

                axios.post('/device/scan', { autoClose: options.autoClose ?? true })
                    .then((response) => {
                        const data = response.data
                        if (!data?.started) {
                            settle((pending) => pending.reject(new Error(data?.error || 'Native scanner is not available')))
                        }
                        // else: leave pending — resolution comes from the native-event listener.
                    })
                    .catch((e: any) => {
                        settle((pending) => pending.reject(new Error(e?.response?.data?.error || e?.message || 'Failed to start scanner')))
                    })
            })
        } finally {
            scanning.value = false
        }
    }

    /**
     * Scan for multiple QR/barcodes in one session. Always manual-close — the
     * native scanner shows a live, deduped list of distinct codes found so
     * far and keeps running until the user taps "Done". Resolves with the
     * full array (in the order each code was first found); rejects if the
     * user backs out via the close (X) button, or on timeout.
     *
     * `options.autoClose` is ignored here — see ScanOptions.autoClose.
     */
    async function scanMultiple(options: ScanOptions = {}): Promise<ScanResult[]> {
        scanning.value = true

        try {
            await ensurePermissions(['android.permission.CAMERA']).catch(() => {})

            return await new Promise<ScanResult[]>((resolve, reject) => {
                const timer = setTimeout(() => {
                    pendingMultiScan = null
                    reject(new Error('Scan timed out'))
                }, options.timeoutMs ?? 300000)

                // Register BEFORE posting — see the identical reasoning in scan() above.
                pendingMultiScan = { resolve, reject, timer }

                axios.post('/device/scan', { multiple: true })
                    .then((response) => {
                        const data = response.data
                        if (!data?.started) {
                            settleMulti((pending) => pending.reject(new Error(data?.error || 'Native scanner is not available')))
                        }
                        // else: leave pending — resolution comes from the native-event listener.
                    })
                    .catch((e: any) => {
                        settleMulti((pending) => pending.reject(new Error(e?.response?.data?.error || e?.message || 'Failed to start scanner')))
                    })
            })
        } finally {
            scanning.value = false
        }
    }

    /** Reject a pending scan (single or multi) early (e.g. the consuming component unmounted). */
    function cancel() {
        settle((pending) => pending.reject(new Error('Scan cancelled')))
        settleMulti((pending) => pending.reject(new Error('Scan cancelled')))
    }

    return {
        scanning,
        scan,
        scanMultiple,
        cancel,
    }
}
