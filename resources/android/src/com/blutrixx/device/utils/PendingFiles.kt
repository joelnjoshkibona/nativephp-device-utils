package com.blutrixx.device.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Files the person picked with the file picker that the page has not confirmed yet (same idea as PendingPhotos).
 *
 * The picked document is copied into the app's own folder the moment the picker returns, because Android may have killed the app
 * while the picker was in front: the page may not exist yet, and the content URI is only readable now. The page imports the copy
 * (event FileSelected carries its path, or Pending lists it at the next start) and confirms with Ack. A copy nobody confirms is
 * deleted after 48 hours.
 */
object PendingFiles {
    private const val PREFS = "blutrixx_device_files"
    private const val KEY = "pending"
    private const val DIR = "pending_files"
    private const val MAX_AGE_MS = 48L * 3600L * 1000L
    private val lock = Any()

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, DIR).apply { mkdirs() }
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun index(ctx: Context): JSONArray = try { JSONArray(prefs(ctx).getString(KEY, "[]")) } catch (e: Exception) { JSONArray() }
    private fun saveIndex(ctx: Context, a: JSONArray) { prefs(ctx).edit().putString(KEY, a.toString()).commit() }
    private fun clean(name: String) = name.replace(Regex("[^A-Za-z0-9._\\- ()]"), "_").ifBlank { "file" }

    /** Copies the document and records it. Returns {id, path, filename, mimeType, size}, or null if it could not be read. */
    fun save(ctx: Context, uri: Uri): JSONObject? = synchronized(lock) {
        val app = ctx.applicationContext
        var name = "document"
        try {
            app.contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) { val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) it.getString(i)?.let { n -> name = n } }
            }
        } catch (e: Exception) { /* keep the default name */ }
        val id = System.currentTimeMillis().toString() + "-" + (1000..9999).random()
        val folder = File(dir(app), id).also { it.mkdirs() }
        val f = File(folder, clean(name))
        try {
            app.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } } ?: return null
        } catch (e: Exception) { folder.deleteRecursively(); return null }
        val meta = JSONObject().put("id", id).put("path", f.absolutePath).put("filename", f.name).put("size", f.length())
            .put("mimeType", app.contentResolver.getType(uri) ?: "application/octet-stream").put("takenAt", System.currentTimeMillis() / 1000)
        val a = index(app); a.put(meta); saveIndex(app, a)
        meta
    }

    /** What is waiting. Drops entries whose file is gone and copies older than 48 hours. */
    fun list(ctx: Context): JSONArray = synchronized(lock) {
        val now = System.currentTimeMillis(); val keep = JSONArray(); val old = index(ctx)
        for (i in 0 until old.length()) {
            val o = old.getJSONObject(i)
            val f = File(o.getString("path"))
            if (!f.exists() || now - o.optLong("takenAt") * 1000L > MAX_AGE_MS) { f.parentFile?.deleteRecursively(); continue }
            keep.put(o)
        }
        if (keep.length() != old.length()) saveIndex(ctx, keep)
        keep
    }

    fun remove(ctx: Context, id: String) = synchronized(lock) {
        val old = index(ctx); val keep = JSONArray()
        for (i in 0 until old.length()) {
            val o = old.getJSONObject(i)
            if (o.getString("id") == id) File(o.getString("path")).parentFile?.deleteRecursively() else keep.put(o)
        }
        saveIndex(ctx, keep)
    }
}
