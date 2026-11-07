package com.blutrixx.device.utils

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Looper
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The name of the area at a position (reverse geocoding) through Android's own Geocoder: street, area or ward, town, district, region, country.
 * On phones with Google services Android asks Google's servers, so it needs the internet and the position leaves the phone: the app decides
 * when that is acceptable (Hodie asks only for its stops, once each). No key or account is needed. Answers within 10 seconds or says why not.
 */
object Geocode {
    private const val WAIT_SECONDS = 10L

    private fun fields(a: Address): Map<String, Any> {
        val m = mutableMapOf<String, Any>("found" to true)
        fun put(k: String, v: String?) { if (!v.isNullOrBlank()) m[k] = v.trim() }
        put("street", a.thoroughfare)
        put("area", a.subLocality)
        put("town", a.locality)
        put("district", a.subAdminArea)
        put("region", a.adminArea)
        put("country", a.countryName)
        put("countryCode", a.countryCode)
        put("feature", a.featureName)
        return m
    }

    @Suppress("DEPRECATION")
    fun lookup(context: Context, lat: Double, lng: Double, lang: String?): Map<String, Any> {
        if (!Geocoder.isPresent()) return mapOf("found" to false, "error" to "NO_GEOCODER")
        if (Looper.myLooper() == Looper.getMainLooper()) return mapOf("found" to false, "error" to "MAIN_THREAD")
        val g = Geocoder(context, if (lang.isNullOrBlank()) Locale.getDefault() else Locale(lang))
        return try {
            val list: List<Address>? = if (Build.VERSION.SDK_INT >= 33) {
                val latch = CountDownLatch(1)
                var got: List<Address>? = null
                var err: String? = null
                g.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) { got = addresses; latch.countDown() }
                    override fun onError(errorMessage: String?) { err = errorMessage ?: "error"; latch.countDown() }
                })
                if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) return mapOf("found" to false, "error" to "TIMEOUT")
                if (err != null) return mapOf("found" to false, "error" to "SERVICE")
                got
            } else {
                g.getFromLocation(lat, lng, 1)
            }
            val a = list?.firstOrNull() ?: return mapOf("found" to false)
            fields(a)
        } catch (e: IOException) {
            mapOf("found" to false, "error" to "NETWORK")   // no internet, or the service did not answer
        } catch (e: IllegalArgumentException) {
            mapOf("found" to false, "error" to "BAD_POSITION")
        } catch (e: Throwable) {
            mapOf("found" to false, "error" to "SERVICE")
        }
    }
}

object GeocodeFunctions {
    /** DeviceUtils.Geocode {lat, lng, lang?}: {found, street?, area?, town?, district?, region?, country?, countryCode?, feature?} or {found:false, error?}. */
    class Lookup(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val lat = (parameters["lat"] as? Number)?.toDouble() ?: (parameters["lat"] as? String)?.toDoubleOrNull()
            val lng = (parameters["lng"] as? Number)?.toDouble() ?: (parameters["lng"] as? String)?.toDoubleOrNull()
            if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) return BridgeResponse.success(mapOf("found" to false, "error" to "BAD_POSITION"))
            return BridgeResponse.success(Geocode.lookup(context, lat, lng, parameters["lang"] as? String))
        }
    }
}
