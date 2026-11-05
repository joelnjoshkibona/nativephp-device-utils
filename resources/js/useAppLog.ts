import axios from 'axios'

export type LogLevel = 'D' | 'I' | 'W' | 'E'

/**
 * The app's log on the phone. Call once when the app starts:
 *
 *   const log = useAppLog()                       // also records JavaScript errors and unhandled promise rejections
 *   log.info('places', 'tracking switched on')    // levels: debug, info, warn, error
 *   await log.read({ limit: 200, level: 'E' })    // { entries, total, errors, detail }
 *   await log.export()                            // save a text file in Downloads/<app> and open the share sheet
 *   const crash = await log.crash()               // { crashed, at, what }: the crash that ended the last run, once
 *
 * Lines are sent in small batches (every 2 seconds, and when the page is hidden). Never put personal content in a message (note text,
 * amounts, names, coordinates): the person exports this file and shares it.
 */
export function useAppLog() {
    let queue: { level: LogLevel; tag: string; message: string }[] = []
    let timer: number | undefined

    const flush = () => {
        if (timer !== undefined) { clearTimeout(timer); timer = undefined }
        if (!queue.length) return
        const entries = queue.splice(0, 200)
        axios.post('/device/log', { entries }).catch(() => {})
    }
    const add = (level: LogLevel, tag: string, message: string) => {
        queue.push({ level, tag, message: String(message).slice(0, 1000) })
        if (level === 'E') flush()
        else if (timer === undefined) timer = window.setTimeout(flush, 2000)
    }

    window.addEventListener('error', (e) => add('E', 'js', `${e.message} at ${(e.filename || '').split('/').pop()}:${e.lineno}:${e.colno}`))
    window.addEventListener('unhandledrejection', (e) => add('E', 'js', 'Unhandled rejection: ' + String((e.reason && (e.reason.message || e.reason)) || 'unknown').slice(0, 200)))
    document.addEventListener('visibilitychange', () => { if (document.hidden) flush() })

    return {
        debug: (tag: string, message: string) => add('D', tag, message),
        info: (tag: string, message: string) => add('I', tag, message),
        warn: (tag: string, message: string) => add('W', tag, message),
        error: (tag: string, message: string) => add('E', tag, message),
        flush,
        read: (opts: { limit?: number; level?: LogLevel } = {}) => axios.post('/device/log/read', opts).then((r) => r.data),
        clear: () => axios.post('/device/log/clear').then((r) => r.data),
        crash: () => axios.post('/device/log/crash').then((r) => r.data),
        setDetail: (on: boolean) => axios.post('/device/log/detail', { on }).then((r) => r.data),
        export: (opts: { share?: boolean; folder?: string } = {}) => { flush(); return axios.post('/device/log/export', opts).then((r) => r.data) },
    }
}
