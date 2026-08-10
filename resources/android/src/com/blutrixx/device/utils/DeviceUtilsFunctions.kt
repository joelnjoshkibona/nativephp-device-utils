package com.blutrixx.device.utils

import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import org.json.JSONArray
import java.io.File

object DeviceUtilsFunctions {

    private const val TAG = "DeviceUtils"

    /**
     * Returns device system bar insets in dp (density-independent pixels).
     * - statusBarHeight: top status bar
     * - navigationBarHeight: bottom navigation bar (3-button or gesture)
     * - navigationMode: "gesture", "buttons", or "unknown"
     */
    class GetInsets(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val density = activity.resources.displayMetrics.density
            val decorView = activity.window.decorView

            val windowInsets = ViewCompat.getRootWindowInsets(decorView)

            if (windowInsets == null) {
                Log.w(TAG, "GetInsets: window insets not available")
                return BridgeResponse.success(mapOf(
                    "statusBarHeight" to 24,
                    "navigationBarHeight" to 48,
                    "navigationMode" to "unknown"
                ))
            }

            val statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navigationBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())

            val statusBarDp = (statusBars.top / density).toInt()
            val navBarDp = (navigationBars.bottom / density).toInt()

            val navigationMode = when {
                navBarDp == 0 -> "gesture"
                navBarDp < 30 -> "gesture"
                else -> "buttons"
            }

            Log.d(TAG, "GetInsets: statusBar=${statusBarDp}dp, navBar=${navBarDp}dp, mode=$navigationMode")

            return BridgeResponse.success(mapOf(
                "statusBarHeight" to statusBarDp,
                "navigationBarHeight" to navBarDp,
                "navigationMode" to navigationMode
            ))
        }
    }

    /**
     * Launch the Android Storage Access Framework file picker.
     * Returns immediately with {launched: true}.
     * The result is delivered asynchronously via:
     *   - FileSelected   { uri, filename, size, mimeType }
     *   - FilePickCancelled {}
     */
    class PickFile(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val mimeType = parameters["mimeType"] as? String ?: "application/pdf"

            activity.runOnUiThread {
                val fm = activity.supportFragmentManager
                if (fm.findFragmentByTag("nativephp_file_picker") != null) {
                    Log.w(TAG, "PickFile: picker already open, ignoring duplicate call")
                    return@runOnUiThread
                }
                fm.beginTransaction()
                    .add(FilePickerFragment.newInstance(mimeType), "nativephp_file_picker")
                    .commitAllowingStateLoss()
            }

            return BridgeResponse.success(mapOf("launched" to true))
        }
    }

    /**
     * Copy a SAF content URI to app-private internal storage.
     * Synchronous — returns the destination path, filename, and byte size.
     *
     * Parameters:
     *   uri      — content URI string from PickFile / FileSelected event
     *   filename — destination filename
     *   subfolder — subdirectory under filesDir (default: "books")
     */
    class CopyToStorage(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val uriString = parameters["uri"] as? String
                ?: return BridgeResponse.error("MISSING_URI", "uri is required")
            val filename = parameters["filename"] as? String
                ?: return BridgeResponse.error("MISSING_FILENAME", "filename is required")
            val subfolder = parameters["subfolder"] as? String ?: "books"

            return try {
                val uri = Uri.parse(uriString)
                val destDir = File(activity.filesDir, subfolder).also { it.mkdirs() }
                val destFile = File(destDir, sanitize(filename))

                activity.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                } ?: return BridgeResponse.error("READ_ERROR", "Could not open input stream for URI")

                Log.d(TAG, "CopyToStorage: ${destFile.length()} bytes → ${destFile.absolutePath}")

                BridgeResponse.success(mapOf(
                    "path"     to destFile.absolutePath,
                    "filename" to destFile.name,
                    "size"     to destFile.length()
                ))
            } catch (e: Exception) {
                Log.e(TAG, "CopyToStorage failed", e)
                BridgeResponse.error("COPY_ERROR", e.message ?: "Failed to copy file")
            }
        }

        private fun sanitize(name: String): String =
            name.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").trim()
    }

    /**
     * Request one or more dangerous Android runtime permissions via the
     * system multi-permission dialog. Permissions already granted are
     * filtered out on the native side before any dialog is shown.
     *
     * Returns immediately with {launched: true}.
     * The result is delivered asynchronously via:
     *   - PermissionsResult { results: {"android.permission.X": true|false, ...}, id }
     *
     * Parameters:
     *   permissions — array of Android permission strings to request
     *   id          — client-supplied correlation id, echoed back in the event
     */
    class RequestPermissions(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val permissions = toStringArray(parameters["permissions"])
            if (permissions.isEmpty()) {
                return BridgeResponse.error("MISSING_PERMISSIONS", "permissions is required")
            }
            val id = parameters["id"] as? String

            activity.runOnUiThread {
                val fm = activity.supportFragmentManager
                if (fm.findFragmentByTag("nativephp_permission_request") != null) {
                    Log.w(TAG, "RequestPermissions: request already in flight, ignoring duplicate call")
                    return@runOnUiThread
                }
                fm.beginTransaction()
                    .add(PermissionRequestFragment.newInstance(permissions, id), "nativephp_permission_request")
                    .commitAllowingStateLoss()
            }

            return BridgeResponse.success(mapOf("launched" to true))
        }

        private fun toStringArray(value: Any?): Array<String> = when (value) {
            is JSONArray -> Array(value.length()) { i -> value.optString(i) }
            is List<*> -> value.filterIsInstance<String>().toTypedArray()
            is Array<*> -> value.filterIsInstance<String>().toTypedArray()
            else -> emptyArray()
        }
    }
}
