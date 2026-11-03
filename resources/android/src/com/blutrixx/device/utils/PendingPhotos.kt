package com.blutrixx.device.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Photos the camera or the gallery produced but the page has not confirmed yet.
 *
 * Android can kill the app while the system picker is in front. The picked photo is then delivered to a page that has not
 * loaded yet, and the PhotoCaptured event is lost. So every photo is written here first; the page collects what it missed
 * (Pending, Take) and confirms what it used (Ack). A photo nobody confirms is deleted after 48 hours.
 */
object PendingPhotos {
    private const val PREFS = "blutrixx_smart_camera"
    private const val KEY = "pending"
    private const val DIR = "smart_camera_pending"
    private const val MAX_AGE_MS = 48L * 3600L * 1000L
    private val lock = Any()

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, DIR).apply { mkdirs() }
    private fun file(ctx: Context, id: String) = File(dir(ctx), id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jpg")
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun index(ctx: Context): JSONArray =
        try { JSONArray(prefs(ctx).getString(KEY, "[]")) } catch (e: Exception) { JSONArray() }

    private fun saveIndex(ctx: Context, a: JSONArray) {
        prefs(ctx).edit().putString(KEY, a.toString()).commit()
    }

    /** Writes the JPEG and records it. Returns the id the page uses to take and acknowledge it. */
    fun save(ctx: Context, bytes: ByteArray, width: Int, height: Int): String = synchronized(lock) {
        val id = System.currentTimeMillis().toString() + "-" + (1000..9999).random()
        file(ctx, id).writeBytes(bytes)
        val a = index(ctx)
        a.put(JSONObject().put("id", id).put("width", width).put("height", height).put("size", bytes.size).put("takenAt", System.currentTimeMillis() / 1000))
        saveIndex(ctx, a)
        id
    }

    /** What is waiting. Drops entries whose file is gone and photos older than 48 hours. */
    fun list(ctx: Context): JSONArray = synchronized(lock) {
        val now = System.currentTimeMillis()
        val keep = JSONArray()
        val old = index(ctx)
        for (i in 0 until old.length()) {
            val o = old.getJSONObject(i)
            val id = o.getString("id")
            val f = file(ctx, id)
            if (!f.exists() || now - o.optLong("takenAt") * 1000L > MAX_AGE_MS) { f.delete(); continue }
            keep.put(o)
        }
        if (keep.length() != old.length()) saveIndex(ctx, keep)
        keep
    }

    fun read(ctx: Context, id: String): ByteArray? = synchronized(lock) {
        val f = file(ctx, id)
        if (f.exists()) f.readBytes() else null
    }

    fun meta(ctx: Context, id: String): JSONObject? = synchronized(lock) {
        val a = index(ctx)
        for (i in 0 until a.length()) if (a.getJSONObject(i).getString("id") == id) return a.getJSONObject(i)
        null
    }

    fun remove(ctx: Context, id: String) = synchronized(lock) {
        file(ctx, id).delete()
        val old = index(ctx)
        val keep = JSONArray()
        for (i in 0 until old.length()) if (old.getJSONObject(i).getString("id") != id) keep.put(old.get(i))
        saveIndex(ctx, keep)
    }
}
