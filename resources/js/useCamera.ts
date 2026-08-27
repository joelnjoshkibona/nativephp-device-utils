import { ref } from 'vue'
import axios from 'axios'
import { ensurePermissions } from './usePermissions'

export interface PhotoResult {
    base64: string
    mimeType: string
}

export interface CaptureOptions {
    /** Reject if no result arrives within this many ms. Default: 120000 */
    timeoutMs?: number
    /**
     * 'camera' (default) opens the native camera; 'gallery' opens the system
     * image picker instead. Both resolve the identical PhotoResult shape —
     * SmartCameraFragment.kt funnels both sources through the same
     * downscale/base64/PhotoCaptured pipeline natively, so nothing else here
     * needs to know which one was used.
     */
    source?: 'camera' | 'gallery'
}

const capturing = ref(false)

type PendingCapture = {
    resolve: (value: PhotoResult) => void
    reject: (reason: Error) => void
    timer: ReturnType<typeof setTimeout>
}

// SmartCamera.Open only ever allows one capture in flight at a time (enforced
// natively), and its PhotoCaptured/CaptureCancelled events carry no `id` to
// correlate against — a single slot is sufficient, no Map needed.
let pendingCapture: PendingCapture | null = null

function settle(run: (pending: PendingCapture) => void) {
    const pending = pendingCapture
    if (!pending) return
    clearTimeout(pending.timer)
    pendingCapture = null
    run(pending)
}

// ── Native event listener via DOM CustomEvent ────────────────────────────────
// NativePHP injects native events directly into the WebView as CustomEvents on
// `document` — this is the verified, authoritative delivery path (see
// useScanner.ts, which documents the same mechanism).
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
        if (eventName.endsWith('Events\\PhotoCaptured')) {
            settle((pending) => pending.resolve({
                base64: payload?.base64,
                mimeType: payload?.mimeType || 'image/jpeg',
            }))
        }
        // Covers an explicit cancel (close/back button, backing out of the
        // gallery picker), a denied CAMERA permission, or a gallery pick that
        // failed to decode — SmartCameraFragment.kt fires the same
        // CaptureCancelled event for all of these, there is no separate
        // per-reason event.
        else if (eventName.endsWith('Events\\CaptureCancelled')) {
            settle((pending) => pending.reject(new Error('Photo capture cancelled')))
        }
    })
}

attachListener()

/**
 * Composable for native photo capture via our in-house SmartCamera plugin
 * (Blutrixx\DeviceUtils\Facades\SmartCamera).
 *
 * Flow: Vue frontend → your Laravel photo() route → SmartCamera::open(mode)
 * → native camera overlay OR system gallery picker (mode picked via
 * `source`) → PhotoCaptured delivered as a `native-event` DOM CustomEvent,
 * already carrying the base64 JPEG directly (no separate file-read
 * round-trip needed) regardless of which source produced it.
 *
 * Gracefully rejects (never hangs) when the native bridge isn't available,
 * when the user cancels or denies permission, or before the timeout elapses.
 */
export function useCamera() {

    async function capturePhoto(options: CaptureOptions = {}): Promise<PhotoResult> {
        const source = options.source ?? 'camera'
        capturing.value = true

        try {
            // Just-in-time permission prompt, right before the native camera
            // is invoked. Best-effort only — must never block the capture
            // attempt, the native camera still enforces the actual permission.
            // Not needed for the gallery source — GetContent's system picker
            // requires no runtime permission.
            if (source === 'camera') {
                await ensurePermissions(['android.permission.CAMERA']).catch(() => {})
            }

            return await new Promise<PhotoResult>((resolve, reject) => {
                const timer = setTimeout(() => {
                    pendingCapture = null
                    reject(new Error('Photo capture timed out'))
                }, options.timeoutMs ?? 120000)

                // Register BEFORE posting, not after awaiting the response —
                // the native side can dispatch PhotoCaptured/CaptureCancelled
                // faster than this axios round-trip resolves (see the
                // equivalent, actually-observed race in usePermissions.ts's
                // requestPermissions()). Registering first closes that race
                // regardless of which settles first.
                pendingCapture = { resolve, reject, timer }

                axios.post('/device/photo', { source })
                    .then((response) => {
                        const data = response.data
                        if (!data?.started) {
                            settle((pending) => pending.reject(new Error(data?.error || 'Native camera is not available')))
                        }
                        // else: leave pending — resolution comes from the native-event listener.
                    })
                    .catch((e: any) => {
                        settle((pending) => pending.reject(new Error(e?.response?.data?.error || e?.message || 'Failed to start camera')))
                    })
            })
        } finally {
            capturing.value = false
        }
    }

    /** Reject a pending capture early (e.g. the consuming component unmounted). */
    function cancel() {
        settle((pending) => pending.reject(new Error('Photo capture cancelled')))
    }

    return {
        capturing,
        capturePhoto,
        cancel,
    }
}
