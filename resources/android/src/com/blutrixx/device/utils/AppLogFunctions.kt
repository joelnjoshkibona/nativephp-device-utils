package com.blutrixx.device.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogFunctions {
    /** Write entries: either level, tag, message, or `entries`: a list of {level, tag, message}. Returns {written}. */
    class Write(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            AppLog.init(context)
            var n = 0
            // the bridge hands a nested list over as a JSONArray of JSONObject (a plain List of Map in other callers): accept both
            val rows = ArrayList<Triple<String, String, String>>()
            fun add(level: Any?, tag: Any?, message: Any?) { rows.add(Triple(((level as? String) ?: "I").take(1).ifEmpty { "I" }, (tag as? String) ?: "app", (message as? String) ?: "")) }
            when (val list = parameters["entries"]) {
                is JSONArray -> for (i in 0 until minOf(list.length(), 200)) list.optJSONObject(i)?.let { add(it.opt("level"), it.opt("tag"), it.opt("message")) }
                is List<*> -> for (e in list.take(200)) (e as? Map<*, *>)?.let { add(it["level"], it["tag"], it["message"]) }
                else -> add(parameters["level"], parameters["tag"], parameters["message"])
            }
            for ((level, tag, message) in rows) { AppLog.write(level[0], tag, message); n++ }
            return BridgeResponse.success(mapOf("written" to n))
        }
    }

    /** The latest entries: {entries: [string], total, errors, detail}. Parameters: limit (default 200), level (D, I, W, E; default D). */
    class Read(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            AppLog.init(context)
            val limit = ((parameters["limit"] as? Number)?.toInt() ?: 200).coerceIn(1, 2000)
            val level = ((parameters["level"] as? String) ?: "D").firstOrNull() ?: 'D'
            return BridgeResponse.success(mapOf(
                "entries" to AppLog.read(limit, level),
                "total" to AppLog.count('D'), "errors" to AppLog.count('E'), "detail" to AppLog.detail(),
            ))
        }
    }

    class Clear(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> { AppLog.init(context); AppLog.clear(); return BridgeResponse.success(mapOf("cleared" to true)) }
    }

    /** The crash that ended the last run, once: {crashed: false} or {crashed: true, at (ms), what}. */
    class Crash(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            AppLog.init(context)
            val c = AppLog.takeCrash() ?: return BridgeResponse.success(mapOf("crashed" to false))
            return BridgeResponse.success(mapOf("crashed" to true, "at" to c.first, "what" to c.second))
        }
    }

    class SetDetail(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            AppLog.init(context); AppLog.setDetail(parameters["on"] as? Boolean ?: false)
            return BridgeResponse.success(mapOf("detail" to AppLog.detail()))
        }
    }

    /**
     * Save the log as a text file in Downloads/<folder> (default: the app's name) and, with share = true, open Android's share sheet for it.
     * Returns {saved, path, uri, shared}.
     */
    class Export(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            AppLog.init(activity)
            val file = AppLog.exportFile(activity)
            val label = activity.applicationInfo.loadLabel(activity.packageManager).toString()
            val name = "$label log " + SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date()) + ".txt"
            val saved = OpenFileFunctions.SaveFile(activity).execute(mapOf(
                "path" to file.path, "mime" to "text/plain", "name" to name, "folder" to ((parameters["folder"] as? String) ?: label),
            ))
            if (saved["status"] == "error") return saved
            val uri = saved["uri"] as? String ?: return BridgeResponse.error("EXPORT_ERROR", "The log could not be saved")
            AppLog.write('I', "app", "log exported" + if (parameters["share"] == true) " and shared" else "")
            if (parameters["share"] != true) return BridgeResponse.success(mapOf("saved" to true, "path" to (saved["path"] ?: ""), "uri" to uri, "shared" to false))
            return try {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, Uri.parse(uri)).putExtra(Intent.EXTRA_SUBJECT, name)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                activity.startActivity(Intent.createChooser(send, "Share the log"))
                BridgeResponse.success(mapOf("saved" to true, "path" to (saved["path"] ?: ""), "uri" to uri, "shared" to true))
            } catch (e: ActivityNotFoundException) {
                BridgeResponse.success(mapOf("saved" to true, "path" to (saved["path"] ?: ""), "uri" to uri, "shared" to false))
            }
        }
    }
}
