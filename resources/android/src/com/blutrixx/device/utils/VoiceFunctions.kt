package com.blutrixx.device.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import java.io.File

/**
 * Records a voice note with the microphone into the app's private files folder (AAC in an .m4a, mono, 64 kbit/s: about 0.5 MB a minute).
 *
 * Start fails with NO_PERMISSION until the person has granted RECORD_AUDIO (ask with DeviceUtils.RequestPermissions first).
 * Stop returns the file's path, its length in seconds and its size; the app copies it into its own storage and deletes this file.
 * Android only lets an app in the foreground use the microphone, so a recording ends when the app is left.
 */
object VoiceRecorder {
    private const val TAG = "VoiceRecorder"
    private const val DIR = "hodie_import"
    private val lock = Any()
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    fun start(ctx: Context): Map<String, Any> = synchronized(lock) {
        val app = ctx.applicationContext
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return BridgeResponse.error("NO_PERMISSION", "The microphone permission has not been granted")
        }
        if (rec != null) return BridgeResponse.error("BUSY", "A recording is already running")
        val dir = File(app.filesDir, DIR).also { it.mkdirs() }
        val f = File(dir, "voice-" + System.currentTimeMillis() + ".m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(app) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            rec = r; file = f; startedAt = System.currentTimeMillis()
            BridgeResponse.success(mapOf("started" to true))
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            try { r.release() } catch (x: Exception) { /* nothing to release */ }
            f.delete()
            BridgeResponse.error("RECORD_ERROR", e.message ?: "The microphone could not be started")
        }
    }

    fun stop(): Map<String, Any> = synchronized(lock) {
        val r = rec ?: return BridgeResponse.error("NOT_RECORDING", "No recording is running")
        val f = file!!
        val ms = System.currentTimeMillis() - startedAt
        rec = null; file = null
        return try {
            r.stop()
            r.release()
            BridgeResponse.success(mapOf("path" to f.absolutePath, "filename" to f.name, "seconds" to Math.max(1L, Math.round(ms / 1000.0)).toInt(), "size" to f.length()))
        } catch (e: RuntimeException) {   // stop() fails when almost nothing was recorded
            try { r.release() } catch (x: Exception) { /* already released */ }
            f.delete()
            BridgeResponse.error("TOO_SHORT", "The recording was too short")
        }
    }

    fun cancel(): Map<String, Any> = synchronized(lock) {
        val r = rec; val f = file
        rec = null; file = null
        if (r != null) { try { r.stop() } catch (e: RuntimeException) { /* nothing recorded */ }; try { r.release() } catch (e: Exception) { /* gone */ } }
        f?.delete()
        BridgeResponse.success(mapOf("cancelled" to (r != null)))
    }

    /** How loud it has been since the last call, 0.0 to 1.0, for a level meter. */
    fun level(): Double = synchronized(lock) { try { (rec?.maxAmplitude ?: 0) / 32767.0 } catch (e: Exception) { 0.0 } }
}

object VoiceFunctions {
    class Start(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> = VoiceRecorder.start(context)
    }

    class Stop(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> = VoiceRecorder.stop()
    }

    class Cancel(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> = VoiceRecorder.cancel()
    }

    class Level(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> = BridgeResponse.success(mapOf("level" to VoiceRecorder.level()))
    }
}
