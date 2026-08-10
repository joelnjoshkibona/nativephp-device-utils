package com.blutrixx.device.utils

import android.util.Log
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
     *   mode    — "scan"  : continuous barcode/QR detection; auto-fires ScanResult on decode
     *             "photo" : manual shutter capture; fires PhotoCaptured on tap
     *   quality — "high" | "medium" | "low"  (photo mode only, default: "high")
     */
    class Open(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val mode    = parameters["mode"]    as? String ?: "scan"
            val quality = parameters["quality"] as? String ?: "high"

            activity.runOnUiThread {
                val fm = activity.supportFragmentManager
                if (fm.findFragmentByTag(FRAGMENT_TAG) != null) {
                    Log.w(TAG, "Open: camera already open, ignoring duplicate call")
                    return@runOnUiThread
                }
                fm.beginTransaction()
                    .add(android.R.id.content, SmartCameraFragment.newInstance(mode, quality), FRAGMENT_TAG)
                    .addToBackStack(FRAGMENT_TAG)
                    .commitAllowingStateLoss()
            }

            Log.d(TAG, "Open: camera launched (mode=$mode, quality=$quality)")
            return BridgeResponse.success(mapOf("launched" to true, "mode" to mode))
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
