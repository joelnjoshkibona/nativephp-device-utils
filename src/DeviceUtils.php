<?php

namespace Blutrixx\DeviceUtils;

class DeviceUtils
{
    /**
     * @return array{statusBarHeight?: int, navigationBarHeight?: int, navigationMode?: string, safeTop?: int, safeRight?: int, safeBottom?: int, safeLeft?: int}
     *         All in dp (CSS px). safe* are the system bars together with any display cutout, per side: pad content by these.
     */
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

    /**
     * Take over Android's Back button for the page (true) or give it back (false). While taken over, each Back press
     * reaches the page as a BackPressed event and nothing else happens. Switch it off when the next press should close the app.
     */
    public function setBackIntercept(bool $enabled): array
    {
        $result = nativephp_call('DeviceUtils.SetBackIntercept', json_encode(['enabled' => $enabled]));

        return $result ? (json_decode($result, true) ?? []) : [];
    }

    /**
     * Start recording a voice note (AAC .m4a in the app's private files folder). Returns {started: true}, or an error with the
     * code NO_PERMISSION until RECORD_AUDIO has been granted (ask with requestPermissions first), or BUSY.
     */
    public function voiceStart(): array
    {
        $result = nativephp_call('DeviceUtils.VoiceStart', '{}');

        return $result ? (json_decode($result, true) ?? []) : [];
    }

    /** Stop the recording. Returns {path, filename, seconds, size}, or the error TOO_SHORT / NOT_RECORDING. */
    public function voiceStop(): array
    {
        $result = nativephp_call('DeviceUtils.VoiceStop', '{}');

        return $result ? (json_decode($result, true) ?? []) : [];
    }

    /** Stop and throw the recording away. */
    public function voiceCancel(): array
    {
        $result = nativephp_call('DeviceUtils.VoiceCancel', '{}');

        return $result ? (json_decode($result, true) ?? []) : [];
    }

    /** How loud it has been since the last call, 0 to 1, for a level meter. */
    public function voiceLevel(): float
    {
        $result = nativephp_call('DeviceUtils.VoiceLevel', '{}');
        $data = $result ? (json_decode($result, true) ?? []) : [];

        return (float) ($data['level'] ?? 0);
    }

    /**
     * Open a file from the app's private storage with whichever app handles its type. The file is copied once into Downloads/<folder>
     * (other apps cannot read the private folder) and opened from there. Returns {opened: true}, or an error with the code
     * NO_APP, UNSUPPORTED (before Android 10), BAD_PATH or COPY_ERROR.
     */
    public function openFile(string $path, string $mime, string $name, string $folder = 'Hodie'): array
    {
        $result = nativephp_call('DeviceUtils.OpenFile', json_encode(['path' => $path, 'mime' => $mime, 'name' => $name, 'folder' => $folder]));

        return $result ? (json_decode($result, true) ?? []) : [];
    }

    /** Picked files the page has not acknowledged yet: {items: [{id, path, filename, mimeType, size}]}. */
    public function filePending(): array
    {
        $result = nativephp_call('DeviceUtils.FilePending', '{}');

        return $result ? (json_decode($result, true) ?? []) : [];
    }

    /** Delete the waiting copy of a picked file once the app has stored its own. */
    public function fileAck(string $id): array
    {
        $result = nativephp_call('DeviceUtils.FileAck', json_encode(['id' => $id]));

        return $result ? (json_decode($result, true) ?? []) : [];
    }

    /**
     * Copy a file from the app's private storage into Downloads/<folder> without opening it (a backup, an export). Returns {saved: true, path},
     * or an error (UNSUPPORTED before Android 10, BAD_PATH, COPY_ERROR).
     */
    public function saveFile(string $path, string $mime, string $name, string $folder = 'Hodie'): array
    {
        $result = nativephp_call('DeviceUtils.SaveFile', json_encode(['path' => $path, 'mime' => $mime, 'name' => $name, 'folder' => $folder]));

        return $result ? (json_decode($result, true) ?? []) : [];
    }
}
