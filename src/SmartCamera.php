<?php

namespace Blutrixx\DeviceUtils;

class SmartCamera
{
    /**
     * Open the camera overlay.
     *
     * @param  string  $mode     'scan'  — continuous barcode/QR detection (fires ScanResult automatically)
     *                           'photo' — manual capture with shutter button (fires PhotoCaptured)
     * @param  string  $quality  JPEG quality for photo mode: 'high' | 'medium' | 'low' (default: 'high')
     */
    public function open(string $mode = 'scan', string $quality = 'high'): array
    {
        $result = nativephp_call('SmartCamera.Open', json_encode([
            'mode'    => $mode,
            'quality' => $quality,
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
}
