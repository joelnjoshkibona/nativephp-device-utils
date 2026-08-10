package com.blutrixx.device.utils

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Unified camera fragment with three modes:
 *
 *  mode = "scan"    — Continuous ML Kit barcode/QR detection.
 *                      No shutter button. Fires ScanResult as soon as a code is read,
 *                      then dismisses automatically.
 *
 *  mode = "photo"   — Manual capture with a shutter button.
 *                      Fires PhotoCaptured { path, base64, width, height, size }.
 *
 *  mode = "gallery" — Skips the camera entirely and launches the system image
 *                      picker (SAF `ACTION_GET_CONTENT`, no runtime permission
 *                      needed). The picked image is decoded, downscaled, and
 *                      re-encoded through the exact same pipeline as "photo"
 *                      mode, so it fires the identical PhotoCaptured event —
 *                      callers don't need to know or care which mode produced it.
 *
 * All three modes fire CaptureCancelled when the user backs out (close button
 * for scan/photo, backing out of the system picker for gallery, or a denied
 * CAMERA permission for scan/photo).
 *
 * Android dependencies required in build.gradle:
 *   implementation "androidx.camera:camera-core:1.3.0"
 *   implementation "androidx.camera:camera-camera2:1.3.0"
 *   implementation "androidx.camera:camera-lifecycle:1.3.0"
 *   implementation "androidx.camera:camera-view:1.3.0"
 *   implementation "com.google.mlkit:barcode-scanning:17.2.0"
 */
class SmartCameraFragment : Fragment() {

    companion object {
        private const val TAG = "SmartCameraFragment"
        private const val CAMERA_PERMISSION = Manifest.permission.CAMERA
        private const val ARG_MODE    = "mode"
        private const val ARG_QUALITY = "quality"

        fun newInstance(mode: String, quality: String) = SmartCameraFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_MODE, mode)
                putString(ARG_QUALITY, quality)
            }
        }
    }

    private lateinit var previewView: PreviewView
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var scanDone = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else { Log.w(TAG, "Camera permission denied"); dispatchCancelled() }
    }

    // Fires on the main thread (ActivityResultRegistry contract). A null uri
    // means the user backed out of the picker with no selection.
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            // Hop to the background executor for the read+decode — same
            // reasoning as capturePhoto(): keep bitmap work off the main
            // thread, then processAndDispatch() hops back for the WebView call.
            cameraExecutor.execute { loadAndDispatchBitmap(uri) }
        } else {
            dispatchCancelled()
        }
    }

    private val mode    get() = arguments?.getString(ARG_MODE,    "scan")  ?: "scan"
    private val quality get() = arguments?.getString(ARG_QUALITY, "high")  ?: "high"

    // ─── View ────────────────────────────────────────────────────────────────

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        val root = FrameLayout(requireContext()).apply {
            setBackgroundColor(0xFF000000.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        previewView = PreviewView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(previewView)

        // Close button (all modes) — for gallery mode this is a fallback exit
        // while the system picker is on top; the picker's own back/cancel is
        // the primary path.
        val closeBtn = ImageButton(requireContext()).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(0x88000000.toInt())
            layoutParams = FrameLayout.LayoutParams(120, 120, Gravity.TOP or Gravity.START).apply {
                topMargin = 60; marginStart = 32
            }
            setOnClickListener { dispatchCancelled() }
        }
        root.addView(closeBtn)

        when (mode) {
            "scan" -> {
                // Scan mode: hint label only — no shutter
                val hint = TextView(requireContext()).apply {
                    text = "Point camera at QR code or barcode"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 14f
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM
                    ).apply { bottomMargin = 140 }
                }
                root.addView(hint)
            }
            "gallery" -> {
                // Gallery mode: no camera UI at all — the system picker is
                // the visible UI while it's on top of this fragment.
                val hint = TextView(requireContext()).apply {
                    text = "Opening gallery…"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 14f
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                }
                root.addView(hint)
            }
            else -> {
                // Photo mode: shutter button
                val shutterBtn = ImageButton(requireContext()).apply {
                    setImageResource(android.R.drawable.ic_menu_camera)
                    setBackgroundColor(0xCCFFFFFF.toInt())
                    layoutParams = FrameLayout.LayoutParams(160, 160, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                        bottomMargin = 80
                    }
                    setOnClickListener { capturePhoto() }
                }
                root.addView(shutterBtn)
            }
        }

        return root
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (mode == "gallery") {
            pickImageLauncher.launch("image/*")
            return
        }

        if (ContextCompat.checkSelfPermission(requireContext(), CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(CAMERA_PERMISSION)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
    }

    // ─── Camera setup ────────────────────────────────────────────────────────

    private fun startCamera() {
        val jpegQuality = when (quality) { "low" -> 50; "medium" -> 75; else -> 95 }

        ProcessCameraProvider.getInstance(requireContext()).addListener({
            val provider = ProcessCameraProvider.getInstance(requireContext()).get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val useCases = mutableListOf<UseCase>(preview)

            if (mode == "photo") {
                imageCapture = ImageCapture.Builder()
                    .setJpegQuality(jpegQuality)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                useCases.add(imageCapture!!)
            } else {
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { ia ->
                        val scanner = BarcodeScanning.getClient()
                        ia.setAnalyzer(cameraExecutor) { proxy -> analyzeFrame(proxy, scanner) }
                    }
                useCases.add(analysis)
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray())
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                dispatchCancelled()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // ─── Scan mode ───────────────────────────────────────────────────────────

    @androidx.camera.core.ExperimentalGetImage
    private fun analyzeFrame(proxy: ImageProxy, scanner: com.google.mlkit.vision.barcode.BarcodeScanner) {
        val mediaImage = proxy.image
        if (mediaImage == null || scanDone) { proxy.close(); return }

        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (!scanDone && barcodes.isNotEmpty()) {
                    val barcode = barcodes.first()
                    val value   = barcode.rawValue ?: return@addOnSuccessListener
                    val format  = formatName(barcode.format)
                    scanDone = true
                    Log.d(TAG, "Scanned: $value ($format)")
                    dispatchScanResult(value, format)
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun formatName(format: Int): String = when (format) {
        Barcode.FORMAT_QR_CODE     -> "QR_CODE"
        Barcode.FORMAT_EAN_13      -> "EAN_13"
        Barcode.FORMAT_EAN_8       -> "EAN_8"
        Barcode.FORMAT_UPC_A       -> "UPC_A"
        Barcode.FORMAT_UPC_E       -> "UPC_E"
        Barcode.FORMAT_CODE_128    -> "CODE_128"
        Barcode.FORMAT_CODE_39     -> "CODE_39"
        Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
        else                       -> "UNKNOWN"
    }

    // ─── Photo mode ──────────────────────────────────────────────────────────

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        val file = File(requireContext().cacheDir, "smart_camera_${System.currentTimeMillis()}.jpg")

        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        val raw = BitmapFactory.decodeFile(file.absolutePath)
                            ?: throw IllegalStateException("Failed to decode captured image")
                        processAndDispatch(raw, file.absolutePath)
                    } catch (e: Exception) {
                        Log.e(TAG, "Photo processing failed", e)
                        activity?.runOnUiThread { dispatchCancelled() }
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    Log.e(TAG, "Image capture failed", e)
                    activity?.runOnUiThread { dispatchCancelled() }
                }
            }
        )
    }

    // ─── Gallery mode ────────────────────────────────────────────────────────

    /** Runs on cameraExecutor (background thread) — see pickImageLauncher. */
    private fun loadAndDispatchBitmap(uri: Uri) {
        try {
            val raw = requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: throw IllegalStateException("Failed to open picked image")
            processAndDispatch(raw, uri.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Gallery image processing failed", e)
            activity?.runOnUiThread { dispatchCancelled() }
        }
    }

    // ─── Shared: downscale + recompress + base64 + dispatch ────────────────────

    /**
     * Shared by "photo" (decoded from the just-captured file) and "gallery"
     * (decoded from the picked content:// URI) — both end up as a plain
     * in-memory Bitmap at this point, so from here on they're identical.
     *
     * Called from a background thread (cameraExecutor) in both cases; hops to
     * the main thread itself before dispatching, since dispatchEvent() calls
     * WebView.evaluateJavascript which requires it.
     */
    private fun processAndDispatch(raw: android.graphics.Bitmap, sourcePath: String) {
        // Downscale + recompress so the base64 payload stays small enough to
        // cross the WebView bridge reliably. Full-resolution images produce
        // multi-MB base64 that breaks evaluateJavascript injection, so the
        // PhotoCaptured event never reaches JS and capture appears to do nothing.
        val maxDim = 1280
        val scale  = maxDim.toFloat() / maxOf(raw.width, raw.height)
        val bmp = if (scale < 1f)
            android.graphics.Bitmap.createScaledBitmap(
                raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true)
        else raw

        val jpegQuality = when (quality) { "low" -> 50; "high" -> 85; else -> 70 }
        val stream = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, jpegQuality, stream)
        val outBytes = stream.toByteArray()

        val b64 = Base64.encodeToString(outBytes, Base64.NO_WRAP)
        val w = bmp.width; val h = bmp.height; val sz = outBytes.size

        if (bmp !== raw) bmp.recycle()
        raw.recycle()

        activity?.runOnUiThread { dispatchPhotoResult(sourcePath, b64, w, h, sz) }
    }

    // ─── Event dispatch ──────────────────────────────────────────────────────

    private fun dispatchScanResult(value: String, format: String) {
        dispatch("Blutrixx\\DeviceUtils\\Events\\ScanResult", JSONObject().apply {
            put("value", value); put("format", format)
        })
        dismiss()
    }

    private fun dispatchPhotoResult(path: String, base64: String, width: Int, height: Int, size: Int) {
        dispatch("Blutrixx\\DeviceUtils\\Events\\PhotoCaptured", JSONObject().apply {
            put("path", path); put("base64", base64)
            put("width", width); put("height", height); put("size", size)
        })
        dismiss()
    }

    private fun dispatchCancelled() {
        dispatch("Blutrixx\\DeviceUtils\\Events\\CaptureCancelled", JSONObject())
        dismiss()
    }

    private fun dispatch(event: String, payload: JSONObject) {
        val host = activity ?: return
        NativeActionCoordinator.dispatchEvent(host, event, payload.toString())
    }

    private fun dismiss() {
        activity?.runOnUiThread {
            try {
                // commitNowAllowingStateLoss executes synchronously so that a subsequent
                // Open call on the same UI-thread frame sees findFragmentByTag == null
                // immediately, instead of finding a stale fragment from a still-pending
                // commitAllowingStateLoss message in the Looper queue.
                parentFragmentManager.beginTransaction()
                    .remove(this)
                    .commitNowAllowingStateLoss()
                parentFragmentManager.popBackStack(
                    "nativephp_smart_camera",
                    androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
                )
            } catch (e: Exception) {
                Log.w(TAG, "dismiss: fragment already detached or state lost", e)
            }
        }
    }
}
