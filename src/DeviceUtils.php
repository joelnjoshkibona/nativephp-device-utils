<?php

namespace Blutrixx\DeviceUtils;

class DeviceUtils
{
    public function getInsets(): array
    {
        $result = nativephp_call('DeviceUtils.GetInsets', '{}');

        return $result ? (json_decode($result, true) ?? []) : [];
    }

    /**
     * Launch the system file picker.
     * Returns {launched: true} immediately.
     * Listen for FileSelected or FilePickCancelled events for the result.
     */
    public function pickFile(string $mimeType = 'application/pdf'): array
    {
        $result = nativephp_call('DeviceUtils.PickFile', json_encode(['mimeType' => $mimeType]));

        return $result ? (json_decode($result, true) ?? []) : [];
    }

    /**
     * Copy a SAF content URI into app-private storage.
     * Returns {path, filename, size} on success.
     */
    public function copyToStorage(string $uri, string $filename, string $subfolder = 'books'): array
    {
        $result = nativephp_call('DeviceUtils.CopyToStorage', json_encode([
            'uri'       => $uri,
            'filename'  => $filename,
            'subfolder' => $subfolder,
        ]));

        return $result ? (json_decode($result, true) ?? []) : [];
    }

    /**
     * Request one or more dangerous Android runtime permissions via the
     * system dialog. Already-granted permissions are filtered out natively
     * before any dialog is shown.
     *
     * Returns {launched: true} immediately.
     * Listen for the PermissionsResult event (correlated by $id) for the result.
     *
     * @param  string[]  $permissions  Android permission strings, e.g. ['android.permission.CAMERA']
     */
    public function requestPermissions(array $permissions, ?string $id = null): array
    {
        $result = nativephp_call('DeviceUtils.RequestPermissions', json_encode([
            'permissions' => $permissions,
            'id'          => $id,
        ]));

        return $result ? (json_decode($result, true) ?? []) : [];
    }
}
