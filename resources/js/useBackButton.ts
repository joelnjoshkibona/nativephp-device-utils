import axios from 'axios'

/**
 * Android's Back button, delivered to the page. Call once when the app starts:
 *
 *   const back = useBackButton(() => { ...close a sheet, go back a screen... })
 *   back.release()   // the next Back press closes the app (for "press back again to leave")
 *   back.take()      // take it over again
 *
 * While taken over, every press arrives here and nothing else happens. Best effort: outside the app nothing happens.
 */
export function useBackButton(onBack: () => void) {
    document.addEventListener('native-event', (e: Event) => {
        const name = (e as CustomEvent).detail?.event as string | undefined
        if (name && name.endsWith('Events\\BackPressed')) onBack()
    })
    const set = (enabled: boolean) => axios.post('/device/back-intercept', { enabled }).catch(() => {})
    void set(true)
    return { take: () => set(true), release: () => set(false) }
}
