package com.blutrixx.device.utils

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import java.io.File

/**
 * Opens a file that lives in the app's private storage with whichever app on the phone handles its type (a PDF viewer, a document app).
 *
 * Other apps cannot read the private folder, so the file is copied once into the phone's Downloads folder (Downloads/<folder>, through
 * MediaStore, no storage permission needed) and opened from there. Asking again for the same file reuses the copy. Needs Android 10 or newer.
 *
 * Parameters: path (a file inside the app's own data folder), mime, name (the name to show), folder (default "Hodie"), open (default true; false
 * only saves the copy into Downloads and does not open it: that is DeviceUtils.SaveFile).
 */
object OpenFileFunctions {
    private const val TAG = "OpenFile"

    class OpenFile(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val path = parameters["path"] as? String ?: return BridgeResponse.error("MISSING_PATH", "path is required")
            val mime = (parameters["mime"] as? String)?.takeIf { it.isNotBlank() && !it.contains("*") } ?: "application/octet-stream"
            val folder = (parameters["folder"] as? String)?.replace(Regex("[^A-Za-z0-9 _-]"), "")?.takeIf { it.isNotBlank() } ?: "Hodie"
            val src = try { File(path).canonicalFile } catch (e: Exception) { return BridgeResponse.error("BAD_PATH", "That file is not available") }
            val root = activity.dataDir.canonicalFile
            if (!src.path.startsWith(root.path + "/") || !src.isFile) return BridgeResponse.error("BAD_PATH", "That file is not available")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return BridgeResponse.error("UNSUPPORTED", "Opening files needs Android 10 or newer")
            val name = ((parameters["name"] as? String) ?: src.name).replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { src.name }
            val openIt = parameters["open"] as? Boolean ?: true

            return try {
                val resolver = activity.contentResolver
                val rel = Environment.DIRECTORY_DOWNLOADS + "/" + folder + "/"
                val table = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                var uri = resolver.query(table, arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE),
                    MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + "=?", arrayOf(name, rel), null)?.use { c ->
                    if (c.moveToFirst() && c.getLong(1) == src.length()) android.content.ContentUris.withAppendedId(table, c.getLong(0)) else null
                }
                if (uri == null) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, rel)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    uri = resolver.insert(table, values) ?: return BridgeResponse.error("COPY_ERROR", "The file could not be prepared")
                    resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                        ?: return BridgeResponse.error("COPY_ERROR", "The file could not be prepared")
                    values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                if (!openIt) return BridgeResponse.success(mapOf("saved" to true, "uri" to uri.toString(), "path" to rel + name))
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    activity.startActivity(view)
                    BridgeResponse.success(mapOf("opened" to true, "uri" to uri.toString()))
                } catch (e: ActivityNotFoundException) {
                    BridgeResponse.error("NO_APP", "No app on this phone opens this kind of file. It was saved in Downloads/" + folder)
                }
            } catch (e: Exception) {
                Log.e(TAG, "OpenFile failed", e)
                BridgeResponse.error("OPEN_ERROR", e.message ?: "The file could not be opened")
            }
        }
    }

    /** Same copy into Downloads/<folder>, without opening it: for a backup or an export the person wants to find in Files. */
    class SaveFile(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> = OpenFile(activity).execute(parameters + mapOf("open" to false))
    }
}
