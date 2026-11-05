<?php

namespace Blutrixx\DeviceUtils\Events;

/**
 * The camera could not be used: it opened and then Android said no (switched off by the phone, a policy, another app using it, a
 * hardware fault). Sent just before CaptureCancelled, so a listener that only knows CaptureCancelled still ends its wait.
 *
 * $reason is one of: camera_disabled, camera_in_use, max_cameras_in_use, camera_fatal, do_not_disturb, stream_config, bind_failed, camera_error.
 */
class CaptureFailed
{
    public function __construct(
        public readonly int $code = 0,
        public readonly string $reason = 'camera_error',
    ) {}
}
