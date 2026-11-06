package com.blutrixx.device.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.telephony.TelephonyManager
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import org.json.JSONObject

/**
 * One reading of the phone's state: battery, network, signal, power saving, Android version, model, free storage.
 * Nothing that identifies the person or the place: no wifi name, no phone number, no SIM or cell identifiers.
 * Every part is best effort (a phone or an Android version may refuse one of them); a missing value is left out, never a crash.
 * `snapshot()` is the short form that other plugins (geo-location) attach to each location fix, by reflection.
 */
object DeviceStatus {
    private inline fun <T> tryOr(default: T, block: () -> T): T = try { block() } catch (e: Throwable) { default }

    /** {percent, charging, plug ("usb"|"ac"|"wireless"|""), temp (C)} from the sticky battery broadcast: no permission needed. */
    private fun battery(context: Context): Map<String, Any> = tryOr(emptyMap()) {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return@tryOr emptyMap()
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1); val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val m = HashMap<String, Any>()
        if (level >= 0 && scale > 0) m["percent"] = level * 100 / scale
        m["charging"] = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        m["plug"] = when { plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "ac"; plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "usb"; plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "wireless"; else -> "" }
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (temp != Int.MIN_VALUE) m["temp"] = temp / 10.0
        m
    }

    /** "wifi" | "mobile" | "ethernet" | "none" */
    private fun network(context: Context): String = tryOr("unknown") {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return@tryOr "none") ?: return@tryOr "none"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "none"
        }
    }

    /** "2G" | "3G" | "4G" | "5G" or "" when Android does not say (Android 11 and up asks for a permission we do not take). */
    private fun generation(context: Context): String = tryOr("") {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        @Suppress("DEPRECATION")
        when (tm.networkType) {
            TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT, TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
            TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_EHRPD, TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
            TelephonyManager.NETWORK_TYPE_LTE, 19 /* LTE_CA */ -> "4G"
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            else -> ""
        }
    }

    /** 0 (none) to 4 (excellent) for the connection in use; -1 when unknown. Wifi: the signal of the network joined (no name read). Mobile: the radio's level. */
    private fun signal(context: Context, net: String): Int = tryOr(-1) {
        when (net) {
            "wifi" -> { val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager; @Suppress("DEPRECATION") WifiManager.calculateSignalLevel(wm.connectionInfo.rssi, 5) }
            else -> { val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager; if (Build.VERSION.SDK_INT >= 28) tm.signalStrength?.level ?: -1 else -1 }
        }
    }

    private fun powerSave(context: Context): Boolean = tryOr(false) { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode }
    private fun airplane(context: Context): Boolean = tryOr(false) { Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1 }

    /** The full reading, for the Phone status screen. */
    fun read(context: Context): Map<String, Any> {
        val b = battery(context); val net = network(context); val m = HashMap<String, Any>()
        b["percent"]?.let { m["battery"] = it }; b["charging"]?.let { m["charging"] = it }; b["plug"]?.let { m["plug"] = it }; b["temp"]?.let { m["batteryTemp"] = it }
        m["network"] = net
        val gen = if (net == "mobile") generation(context) else ""
        if (gen.isNotEmpty()) m["generation"] = gen
        val sig = signal(context, net); if (sig >= 0) m["signal"] = sig
        m["powerSave"] = powerSave(context); m["airplane"] = airplane(context)
        m["android"] = Build.VERSION.RELEASE ?: ""; m["sdk"] = Build.VERSION.SDK_INT
        m["model"] = Build.MODEL ?: ""; m["manufacturer"] = Build.MANUFACTURER ?: ""
        tryOr(null) { val s = StatFs(Environment.getDataDirectory().path); Pair(s.availableBytes, s.totalBytes) }?.let { m["storageFree"] = it.first; m["storageTotal"] = it.second }
        return m
    }

    /** The short form kept with each location fix: {"b":85,"c":1,"n":"w","g":"4G","s":3,"p":0}; n: w wifi, m mobile, e ethernet, x none. */
    @JvmStatic fun snapshot(context: Context): String {
        val b = battery(context); val net = network(context); val o = JSONObject()
        (b["percent"] as? Int)?.let { o.put("b", it) }; (b["charging"] as? Boolean)?.let { o.put("c", if (it) 1 else 0) }
        o.put("n", when (net) { "wifi" -> "w"; "mobile" -> "m"; "ethernet" -> "e"; else -> "x" })
        if (net == "mobile") generation(context).takeIf { it.isNotEmpty() }?.let { o.put("g", it) }
        signal(context, net).takeIf { it >= 0 }?.let { o.put("s", it) }
        o.put("p", if (powerSave(context)) 1 else 0)
        return o.toString()
    }
}

object DeviceStatusFunctions {
    /** DeviceUtils.GetStatus: one full reading of the phone's state. */
    class Get(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> = BridgeResponse.success(DeviceStatus.read(context))
    }
}
