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
}
