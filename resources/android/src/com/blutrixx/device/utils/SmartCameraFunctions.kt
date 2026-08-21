package com.blutrixx.device.utils

import android.content.Context
import android.util.Log
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse

object SmartCameraFunctions {

    private const val TAG = "SmartCamera"
    private const val FRAGMENT_TAG = "nativephp_smart_camera"

    /**
     * Open the camera overlay.
     *
     * Parameters:
     *   mode      — "scan"  : barcode/QR detection; fires ScanResult (or ScanResults, see multiple) on decode
     *               "photo" : manual shutter capture; fires PhotoCaptured on tap
     *   quality   — "high" | "medium" | "low"  (photo mode only, default: "high")
     *   multiple  — scan mode only, default false. true: keep scanning and accumulate
     *               every distinct code found until the user taps Done, firing
     *               ScanResults (plural) with the whole array. Manual close only —
     *               autoClose is ignored when this is true.
     *   autoClose — scan mode, single (multiple=false) only, default true. false shows
     *               a "Found: <value>" confirm/retry UI instead of closing immediately.
     */
    class Open(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val mode      = parameters["mode"]      as? String  ?: "scan"
            val quality   = parameters["quality"]   as? String  ?: "high"
            val multiple  = parameters["multiple"]  as? Boolean ?: false
            val autoClose = parameters["autoClose"] as? Boolean ?: true

            activity.runOnUiThread {
                val fm = activity.supportFragmentManager
                if (fm.findFragmentByTag(FRAGMENT_TAG) != null) {
                    Log.w(TAG, "Open: camera already open, ignoring duplicate call")
                    return@runOnUiThread
                }
                fm.beginTransaction()
                    .add(android.R.id.content, SmartCameraFragment.newInstance(mode, quality, multiple, autoClose), FRAGMENT_TAG)
                    .addToBackStack(FRAGMENT_TAG)
                    .commitAllowingStateLoss()
            }

            Log.d(TAG, "Open: camera launched (mode=$mode, quality=$quality, multiple=$multiple, autoClose=$autoClose)")
            return BridgeResponse.success(mapOf("launched" to true, "mode" to mode))
        }
    }

    /**
     * Pre-warm CameraX in the background so a later Open() binds the camera
     * noticeably faster.
     *
     * ProcessCameraProvider.getInstance(context) does real work the first
     * time it's called in the process — binding to the system CameraX
     * service and enumerating camera hardware — before SmartCameraFragment
     * even exists. That binding is the actual source of "camera takes a
     * while to open" (the Open() call above and the fragment transaction it
     * does are both near-instant; confirmed via logcat during live device
     * testing — SmartCamera.Open returns in well under a second either way).
     * ProcessCameraProvider is a singleton with its own cache, so calling
     * getInstance() here just resolves/caches the future ahead of time;
     * SmartCameraFragment.startCamera()'s own getInstance().get() call later
     * either returns immediately or joins this same in-flight future — no
     * duplicate work, no UI shown, fire-and-forget.
     *
     * Also calls SmartCameraFragment.ensureCameraXConfigured() first — see
     * its doc comment for why: on at least one real device, CameraX's
     * default front-camera probe (which this app never needs — always
     * CameraSelector.DEFAULT_BACK_CAMERA) failed and retried for ~5.7s
     * before giving up. Limiting CameraX to the back camera up front
     * eliminates that retry storm entirely rather than just relocating it
     * here in the background.
     *
     * Callers: resources/js/src/composables/useCamera.ts's warmCamera(),
     * called from onMounted() on pages that use scan or photo capture — the
     * user usually spends time on earlier wizard steps first, so this
     * finishes well before they actually reach the scan/photo button.
     */
    class Warm(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            try {
                SmartCameraFragment.ensureCameraXConfigured(context)
                ProcessCameraProvider.getInstance(context).addListener(
                    { Log.d(TAG, "Warm: CameraX provider ready") },
                    ContextCompat.getMainExecutor(context)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Warm: failed to pre-warm CameraX (non-fatal)", e)
            }
            return BridgeResponse.success(mapOf("warming" to true))
        }
    }

    /**
     * Dismiss the camera overlay if open.
     */
    class Close(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            activity.runOnUiThread {
                val fm = activity.supportFragmentManager
                val fragment = fm.findFragmentByTag(FRAGMENT_TAG)
                if (fragment != null) {
                    fm.beginTransaction().remove(fragment).commitAllowingStateLoss()
                    fm.popBackStack(FRAGMENT_TAG, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
                    Log.d(TAG, "Close: camera dismissed")
                }
            }
            return BridgeResponse.success(mapOf("closed" to true))
        }
    }
}
