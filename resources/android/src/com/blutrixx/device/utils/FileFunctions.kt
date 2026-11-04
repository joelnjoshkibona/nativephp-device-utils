package com.blutrixx.device.utils

import android.content.Context
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import org.json.JSONObject

object FileFunctions {
    /** Picked files the page has not acknowledged yet: {items: [{id, path, filename, mimeType, size}]}. */
    class Pending(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val a = PendingFiles.list(context)
            return BridgeResponse.success(mapOf("items" to (0 until a.length()).map { i -> a.getJSONObject(i).let { o -> o.keys().asSequence().associateWith { k -> o.get(k) } } }))
        }
    }

    /** Delete a waiting copy once the page has stored its own. */
    class Ack(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = parameters["id"] as? String ?: return BridgeResponse.error("MISSING_ID", "id is required")
            PendingFiles.remove(context, id)
            return BridgeResponse.success(mapOf("removed" to true))
        }
    }
}
