import { ref } from 'vue'
import axios from 'axios'

export type PermissionResults = Record<string, boolean>

export interface RequestPermissionsOptions {
    /** Reject if no result arrives within this many ms. Default: 30000 */
    timeoutMs?: number
}

const requesting = ref(false)

type PendingRequest = {
    resolve: (value: PermissionResults) => void
    reject: (reason: Error) => void
    timer: ReturnType<typeof setTimeout>
}

const pendingRequests = new Map<string, PendingRequest>()

function settle(id: string, run: (pending: PendingRequest) => void) {
    const pending = pendingRequests.get(id)
    if (!pending) return
    clearTimeout(pending.timer)
    pendingRequests.delete(id)
    run(pending)
}

// ── Native event listener via DOM CustomEvent ────────────────────────────────
// NativePHP injects native events directly into the WebView as CustomEvents on
// `document` — this is the verified, authoritative delivery path (see
// useScanner.ts / useCamera.ts, which document the same mechanism).
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

        const id = payload?.id as string | undefined
        if (!id) return

        // Actual delivered name is the full FQCN tail including the `Events\`
        // sub-namespace (verified live: 'Blutrixx\DeviceUtils\Events\PermissionsResult').
        if (eventName.endsWith('Events\\PermissionsResult')) {
            settle(id, (pending) => pending.resolve((payload.results ?? {}) as PermissionResults))
        }
    })
}

attachListener()

function generateId(): string {
    return typeof crypto !== 'undefined' && crypto.randomUUID
        ? crypto.randomUUID()
        : `perm-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

/**
 * Composable for requesting Android dangerous runtime permissions via
 * blutrixx/nativephp-device-utils.
 *
 * Named useDevicePermissions, not usePermissions -- a name this generic is
 * near-guaranteed to collide with a consuming app's own RBAC permission
 * composable (confirmed live: exactly this collision silently broke every
 * generated page in one consuming app before the naming was fixed there).
 * Renaming on import at the call site is not a substitute -- a `usePermissions`
 * export invites exactly that collision by construction.
 *
 * Flow: Vue frontend → your Laravel /device/request-permissions route →
 * Blutrixx\DeviceUtils\Facades\DeviceUtils::requestPermissions() → native
 * multi-permission dialog (or immediate resolve if everything is already
 * granted) → result delivered back as a `native-event` DOM CustomEvent,
 * matched here by request id.
 *
 * Gracefully rejects (never hangs) when the native bridge isn't available,
 * or when nothing arrives before the timeout.
 */
export function useDevicePermissions() {

    async function requestPermissions(permissions: string[], options: RequestPermissionsOptions = {}): Promise<PermissionResults> {
        const id = generateId()
        requesting.value = true

        try {
            return await new Promise<PermissionResults>((resolve, reject) => {
                const timer = setTimeout(() => {
                    pendingRequests.delete(id)
                    reject(new Error('Permission request timed out'))
                }, options.timeoutMs ?? 30000)

                // Register BEFORE posting, not after awaiting the response. The
                // native side can dispatch PermissionsResult (e.g. the
                // "already granted, skip dialog" fast path) in single-digit
                // milliseconds — faster than this axios round-trip through the
                // WebView resolves. Registering after the await lost that race:
                // the native-event listener would find nothing in
                // pendingRequests yet, silently drop the event, and this
                // promise would sit until the 30s timeout regardless of how
                // fast the native side actually responded (confirmed live:
                // every scan()/capturePhoto() call ate the full 30s here
                // before proceeding anyway). Registering first closes the
                // race — the listener is already primed no matter which
                // settles first.
                pendingRequests.set(id, { resolve, reject, timer })

                axios.post('/device/request-permissions', { id, permissions })
                    .then((response) => {
                        const data = response.data
                        if (!data?.started) {
                            settle(id, (pending) => pending.reject(new Error(data?.error || 'Native permission bridge is not available')))
                        }
                        // else: leave pending — resolution comes from the native-event listener.
                    })
                    .catch((e: any) => {
                        settle(id, (pending) => pending.reject(new Error(e?.response?.data?.error || e?.message || 'Failed to request permissions')))
                    })
            })
        } finally {
            requesting.value = false
        }
    }

    return {
        requesting,
        requestPermissions,
    }
}

/**
 * Parse the Android OS version (e.g. 13) out of the WebView's userAgent.
 * Returns null when it can't be found (desktop browser, iOS, etc.) — the
 * caller should treat that as "nothing to request here".
 *
 * Exported so other composables can gate their own version-specific
 * permission requests without reimplementing the userAgent regex.
 */
export function detectAndroidOsVersion(): number | null {
    const match = navigator.userAgent.match(/Android\s+(\d+)/i)
    if (!match) return null
    const version = parseInt(match[1], 10)
    return Number.isNaN(version) ? null : version
}

/**
 * Best-effort, in-context request for specific dangerous permissions —
 * intended to be awaited immediately before the action that needs them
 * (camera scan, photo capture, Bluetooth printer connect), right where the
 * native bridge is guaranteed to be ready. This is an additional safety net
 * on top of requestStartupPermissions(), which fires once at app boot and
 * can race the native bridge's readiness.
 *
 * Never throws and never hangs the caller: resolves to an empty object
 * whenever the bridge is unavailable, the request errors out, or it times
 * out. The native layer still enforces the actual OS permission regardless
 * of what this resolves to, so callers should proceed with their action
 * either way and let the native side reject if permission was denied.
 */
export async function ensurePermissions(permissions: string[]): Promise<PermissionResults> {
    try {
        const { requestPermissions } = useDevicePermissions()
        return await requestPermissions(permissions)
    } catch {
        return {}
    }
}

/**
 * Request the dangerous permissions your app needs, scoped to what's
 * actually applicable on the Android version currently running — never
 * requests a permission the OS doesn't gate at runtime.
 *
 * Android OS version → API level used for gating:
 *   - Android 12 = API 31 (BLUETOOTH_CONNECT becomes a runtime permission)
 *   - Android 13 = API 33 (POST_NOTIFICATIONS becomes a runtime permission)
 *
 * Intended to be called once, fire-and-forget, at app startup. Never throws
 * — a missing bridge (desktop browser, iOS) or a failed/timed-out request
 * must never break startup. Only requests android.permission.CAMERA by
 * default -- add other permissions your app needs at the call site.
 */
export async function requestStartupPermissions(extraPermissions: string[] = []): Promise<void> {
    try {
        const androidVersion = detectAndroidOsVersion()
        if (androidVersion === null) return

        const permissions = ['android.permission.CAMERA', ...extraPermissions]
        if (androidVersion >= 12) permissions.push('android.permission.BLUETOOTH_CONNECT')
        if (androidVersion >= 13) permissions.push('android.permission.POST_NOTIFICATIONS')

        const { requestPermissions } = useDevicePermissions()
        await requestPermissions(permissions)
    } catch {
        // Swallow everything — startup must never depend on this succeeding.
    }
}
