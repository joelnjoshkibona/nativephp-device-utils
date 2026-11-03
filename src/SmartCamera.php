<?php

namespace Blutrixx\DeviceUtils;

class SmartCamera
{
    /**
     * Open the camera overlay.
     *
     * @param  string  $mode       'scan'  — barcode/QR detection (fires ScanResult, or ScanResults if $multiple)
     *                             'photo' — manual capture with shutter button (fires PhotoCaptured)
     * @param  string  $quality    JPEG quality for photo mode: 'high' | 'medium' | 'low' (default: 'high')
     * @param  bool    $multiple   scan mode only. true: keep scanning and accumulate every distinct
     *                             code found until the user taps Done, firing ScanResults (plural)
     *                             with the whole array. Manual close only — $autoClose is ignored.
     * @param  bool    $autoClose  scan mode, single ($multiple=false) only. false shows a
     *                             "Found: <value>" confirm/retry UI instead of closing immediately.
     */
    public function open(string $mode = 'scan', string $quality = 'high', bool $multiple = false, bool $autoClose = true): array
    {
        $result = nativephp_call('SmartCamera.Open', json_encode([
            'mode'      => $mode,
            'quality'   => $quality,
            'multiple'  => $multiple,
            'autoClose' => $autoClose,
        ]));

        return $result ? (json_decode($result, true) ?? []) : [];
    }

    /**
     * Dismiss the camera overlay if it is currently open.
     */
    public function close(): array
    {
        $result = nativephp_call('SmartCamera.Close', '{}');

        return $result ? (json_decode($result, true) ?? []) : [];
    }

    /**
     * Pre-warm CameraX in the background (no UI shown) so a later open()
     * binds the camera faster. Fire-and-forget — safe to call speculatively.
     */
    public function warm(): array
    {
        $result = nativephp_call('SmartCamera.Warm', '{}');

        return $result ? (json_decode($result, true) ?? []) : [];
    }

    /**
     * Photos the camera or gallery produced that the page has not acknowledged. They survive the app being killed while
     * the system picker was open. Each item: id, width, height, size, takenAt.
     *
     * @return array{count?: int, items: array<int, array<string, mixed>>}
     */
    public function pending(): array
    {
        $result = nativephp_call('SmartCamera.Pending', '{}');
        $r = $result ? (json_decode($result, true) ?? []) : [];
        $r['items'] = isset($r['items']) && is_string($r['items']) ? (json_decode($r['items'], true) ?? []) : [];

        return $r;
    }

    /** One waiting photo as base64 (id, base64, mimeType, width, height, size). It stays until ack(). */
    public function take(string $id): array
    {
        $result = nativephp_call('SmartCamera.Take', json_encode(['id' => $id]));

        return $result ? (json_decode($result, true) ?? []) : [];
    }

    /** Delete a waiting photo once it has been used. */
    public function ack(string $id): array
    {
        $result = nativephp_call('SmartCamera.Ack', json_encode(['id' => $id]));

        return $result ? (json_decode($result, true) ?? []) : [];
    }
}
