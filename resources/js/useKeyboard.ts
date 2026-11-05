import axios from 'axios'

export interface KeyboardState {
    /** the on-screen keyboard is up */
    visible: boolean
    /** how much of the screen it covers, in CSS px, from the bottom edge up (0 while it is down) */
    height: number
    /** true when the height came from Android, false when it is an estimate (a keyboard is about 40% of the screen) */
    exact: boolean
}

/**
 * Keep the page above the on-screen keyboard. Call once when the app starts:
 *
 *   const kb = useKeyboard((k) => { ... })        // k = { visible, height, exact }, on every change
 *   // CSS: .sheet { bottom: var(--kb, 0px) }  .page { padding-bottom: var(--kb, 0px) }
 *   kb.reveal()                                    // scroll the field being typed in into the part of the screen above the keyboard
 *
 * Why: the shell draws edge to edge and some phones (Android 10) neither resize the web view nor shrink `visualViewport` when the keyboard
 * opens, so it simply lies on top of the lower part of the page and hides the field being typed in. The shell does add the class
 * `keyboard-visible` to <body> while the keyboard is up; this asks Android for its height (DeviceUtils.GetKeyboard) when that class appears,
 * sets the CSS variable `--kb` on <html> to it (px, "0px" while the keyboard is down) and sets `--kb-vh` to the height left above it.
 * Where `visualViewport` does shrink, that is used instead. If Android cannot say, the height is estimated (exact: false).
 */
export function useKeyboard(onChange?: (k: KeyboardState) => void) {
    const root = document.documentElement
    const vv = window.visualViewport
    let state: KeyboardState = { visible: false, height: 0, exact: true }
    let ask = 0

    const apply = (next: KeyboardState) => {
        const changed = next.visible !== state.visible || next.height !== state.height || next.exact !== state.exact
        state = next
        root.style.setProperty('--kb', next.height + 'px')
        root.style.setProperty('--kb-vh', Math.max(0, window.innerHeight - next.height) + 'px')
        if (changed) {
            onChange?.(next)
            if (next.visible) reveal(250)
        }
    }

    const classUp = () => !!document.body && document.body.classList.contains('keyboard-visible')
    const viewportCovered = () => (vv ? Math.max(0, Math.round(window.innerHeight - vv.height - vv.offsetTop)) : 0)

    const update = () => {
        const covered = viewportCovered()
        if (covered > 120) return apply({ visible: true, height: covered, exact: true })
        if (!classUp()) return apply({ visible: false, height: 0, exact: true })
        // the keyboard is up but the viewport did not change: ask Android, a few times (the value can lag the class by a frame)
        const mine = ++ask
        const guess = Math.round(Math.min(450, Math.max(250, window.innerHeight * 0.42)))
        apply({ visible: true, height: state.visible ? state.height : guess, exact: false })
        const tries = [0, 120, 300, 700]
        const next = (i: number) => {
            if (mine !== ask || i >= tries.length) return
            setTimeout(() => {
                axios.post('/device/keyboard').then((r) => {
                    if (mine !== ask) return
                    const h = Number(r.data?.height) || 0
                    if (r.data?.visible && h > 120) apply({ visible: true, height: h, exact: true })
                    else next(i + 1)
                }).catch(() => next(i + 1))
            }, tries[i])
        }
        next(0)
    }

    const isField = (t: Element | null) => !!t && (/^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName) || (t as HTMLElement).isContentEditable)
    /** scroll the field being typed in to the middle of the part of the screen above the keyboard */
    const reveal = (delay = 0) => setTimeout(() => {
        const t = document.activeElement
        if (state.visible && isField(t)) (t as HTMLElement).scrollIntoView({ block: 'center', behavior: 'smooth' })
    }, delay)

    vv?.addEventListener('resize', update)
    vv?.addEventListener('scroll', update)
    document.addEventListener('focusin', (e) => { if (state.visible && isField(e.target as Element)) reveal(250) })
    const watch = () => { new MutationObserver(update).observe(document.body, { attributes: true, attributeFilter: ['class'] }); update() }
    if (document.body) watch(); else document.addEventListener('DOMContentLoaded', watch)

    return { state: () => state, reveal }
}
