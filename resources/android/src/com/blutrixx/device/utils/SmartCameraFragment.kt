package com.blutrixx.device.utils

import android.Manifest
import android.content.Context
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
import androidx.camera.camera2.Camera2Config
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
 *  mode = "scan"    — ML Kit barcode/QR detection. No shutter button.
 *                      Default (multiple=false, autoClose=true): fires ScanResult
 *                      as soon as a code is read, then dismisses automatically.
 *                      multiple=false, autoClose=false: stops after the first
 *                      decode but shows a "Found: <value>" confirm/retry UI
 *                      instead of closing — fires ScanResult only once the user
 *                      taps "Use this code" (or keeps scanning via "Scan again").
 *                      multiple=true: keeps scanning indefinitely, accumulating
 *                      every distinct code found (deduped by value) in a live
 *                      on-screen list, until the user taps "Done" — fires
 *                      ScanResults (plural, an array) — autoClose is ignored,
 *                      manual close is the only option in this mode.
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
        private const val ARG_MODE       = "mode"
        private const val ARG_QUALITY    = "quality"
        private const val ARG_MULTIPLE   = "multiple"
        private const val ARG_AUTO_CLOSE = "autoClose"

        /**
         * @param multiple  scan mode only. false (default): stop after the first
         *                  decode. true: keep scanning, accumulate every distinct
         *                  code found (deduped by value) until the user taps Done —
         *                  autoClose is ignored/forced-false in this mode, since
         *                  manual close is the only option for a multi-scan session.
         * @param autoClose scan mode, single (multiple=false) only. true (default):
         *                  resolve and dismiss immediately on the first decode
         *                  (today's original behavior). false: stop scanning but
         *                  show a "Found: <value>" confirm/retry UI instead of
         *                  closing — the user taps "Use this code" to accept or
         *                  "Scan again" to keep looking.
         */
        fun newInstance(mode: String, quality: String, multiple: Boolean = false, autoClose: Boolean = true) = SmartCameraFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_MODE, mode)
                putString(ARG_QUALITY, quality)
                putBoolean(ARG_MULTIPLE, multiple)
                putBoolean(ARG_AUTO_CLOSE, autoClose)
            }
        }

        @Volatile private var cameraXConfigured = false

        /**
         * The only camera(s) CameraX is allowed to consider, app-wide, for
         * this app's entire process lifetime — see ensureCameraXConfigured()
         * below for why this can only be set ONCE and can't vary per capture
         * session. Explicit single source of truth: bindToLifecycle() in
         * startCamera() below always passes this exact value too, so there's
         * one place to change if this app's camera needs ever change.
         *
         * Defaults to back — every current use (QR/barcode scan, receipt
         * photo) is inherently a back-camera activity, and nothing in this
         * plugin has ever requested the front camera. If a genuine
         * front-camera need appears later (e.g. a worker selfie/ID-check
         * flow), this is the one place to change — but note that switching
         * to CameraSelector.DEFAULT_FRONT_CAMERA or a selector that allows
         * both directly trades back the ~5.7s front-camera-probe retry cost
         * this whole mechanism exists to eliminate on devices that don't
         * report a front camera correctly (see the failure mode documented
         * below). There is no way to make this a genuine per-call runtime
         * choice without paying that cost on every open, since
         * ProcessCameraProvider.configureInstance() is a one-shot,
         * process-lifetime setting, not a per-session one.
         */
        private val AVAILABLE_CAMERAS: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        /**
         * Configure CameraX to only ever probe for AVAILABLE_CAMERAS, exactly
         * once, before the very first ProcessCameraProvider.getInstance()
         * call anywhere in the process — configureInstance() throws if
         * called after CameraX has already started initializing, so this
         * must win the race against both call sites below.
         *
         * Without this, CameraX's default CameraValidator ALSO probes for a
         * front camera on every cold start — this app never requests one
         * (see AVAILABLE_CAMERAS above) but CameraX doesn't know that ahead
         * of time. On at least one real test device that front-camera probe
         * failed outright (device reports zero LENS_FACING_FRONT cameras)
         * and CameraX retried ~11 times over ~5.7 seconds before giving up
         * and proceeding anyway — confirmed via logcat
         * ("CameraIdListIncorrectException: Expected camera missing from
         * device" / "Retry init" / "The device might underreport the amount
         * of the cameras") — a real, measured, avoidable cost paid on every
         * single camera open, not generic CameraX cold-start overhead.
         *
         * setAvailableCamerasLimiter tells CameraX up front which camera(s)
         * actually matter, so it never attempts that front-camera probe.
         *
         * Called from both SmartCameraFunctions.Warm (the normal path, well
         * before the user taps scan/photo) and here in startCamera() (safety
         * net in case a camera/scan is ever opened without a prior warm
         * call). Thread-safe via double-checked locking; configureInstance()
         * itself is a cheap synchronous call — it just records config for
         * the next getInstance(), no camera I/O happens here.
         */
        fun ensureCameraXConfigured(context: Context) {
            if (cameraXConfigured) return
            synchronized(this) {
                if (cameraXConfigured) return
                cameraXConfigured = true
                try {
                    ProcessCameraProvider.configureInstance(
                        CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
                            .setAvailableCamerasLimiter(AVAILABLE_CAMERAS)
                            .build()
                    )
                } catch (e: Exception) {
                    // Most likely cause: getInstance() already ran elsewhere before this
                    // call won the race — non-fatal, CameraX just uses its own defaults.
                    Log.w(TAG, "ensureCameraXConfigured: failed (CameraX may already be initializing)", e)
                }
            }
        }
    }

    private lateinit var previewView: PreviewView
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null

    // ─── Scan-mode state ───────────────────────────────────────────────────
    // Single-result path (multiple=false): locks after the first decode so
    // later frames are ignored, regardless of autoClose.
    private var singleResultLocked = false
    private var pendingSingleResult: Pair<String, String>? = null

    // Multi-result path (multiple=true): every distinct value found so far,
    // insertion-ordered, deduped by value — re-scanning the same code is a
    // no-op, not a second entry.
    private val foundCodes = LinkedHashMap<String, String>()

    // Scan-mode views that need updating after the initial layout — null in
    // the modes/branches that don't use them.
    private var scanHintView: TextView? = null
    private var confirmTextView: TextView? = null
    private var confirmButtonsRow: android.widget.LinearLayout? = null
    private var foundCodesListContainer: android.widget.LinearLayout? = null
    private var doneButton: android.widget.Button? = null

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

    private val mode       get() = arguments?.getString(ARG_MODE,    "scan")  ?: "scan"
    private val quality    get() = arguments?.getString(ARG_QUALITY, "high")  ?: "high"
    private val multiple   get() = arguments?.getBoolean(ARG_MULTIPLE, false) ?: false
    private val autoClose  get() = arguments?.getBoolean(ARG_AUTO_CLOSE, true) ?: true

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
            "scan" -> when {
                multiple -> {
                    // Multi-scan: hint + a live scrollable list of distinct codes
                    // found so far + a "Done (N)" button (disabled until at least
                    // one code is found). No auto-close — see newInstance() docs.
                    val hint = TextView(requireContext()).apply {
                        text = "Point camera at codes — tap Done when finished"
                        setTextColor(0xFFFFFFFF.toInt())
                        textSize = 14f
                        gravity = Gravity.CENTER
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP
                        ).apply { topMargin = 220 }
                    }
                    root.addView(hint)

                    val listContainer = android.widget.LinearLayout(requireContext()).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                    }
                    foundCodesListContainer = listContainer
                    val scrollView = android.widget.ScrollView(requireContext()).apply {
                        addView(listContainer)
                        setBackgroundColor(0x99000000.toInt())
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT, 400, Gravity.BOTTOM
                        ).apply { bottomMargin = 200 }
                    }
                    root.addView(scrollView)

                    val doneBtn = android.widget.Button(requireContext()).apply {
                        text = "Done (0)"
                        isEnabled = false
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                        ).apply { bottomMargin = 80 }
                        setOnClickListener { finishMultiScan() }
                    }
                    doneButton = doneBtn
                    root.addView(doneBtn)
                }
                !autoClose -> {
                    // Single scan, manual close: hint until the first decode, then
                    // swap to a "Found: <value>" confirm/retry pair.
                    val hint = TextView(requireContext()).apply {
                        text = "Point camera at QR code or barcode"
                        setTextColor(0xFFFFFFFF.toInt())
                        textSize = 14f
                        gravity = Gravity.CENTER
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM
                        ).apply { bottomMargin = 260 }
                    }
                    scanHintView = hint
                    root.addView(hint)

                    val confirmText = TextView(requireContext()).apply {
                        setTextColor(0xFFFFFFFF.toInt())
                        textSize = 14f
                        gravity = Gravity.CENTER
                        visibility = View.GONE
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM
                        ).apply { bottomMargin = 220 }
                    }
                    confirmTextView = confirmText
                    root.addView(confirmText)

                    val confirmRow = android.widget.LinearLayout(requireContext()).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        visibility = View.GONE
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                        ).apply { bottomMargin = 80 }
                        val useBtn = android.widget.Button(requireContext()).apply {
                            text = "Use this code"
                            setOnClickListener { confirmSingleResult() }
                        }
                        val againBtn = android.widget.Button(requireContext()).apply {
                            text = "Scan again"
                            setOnClickListener { resumeScanning() }
                        }
                        addView(useBtn)
                        addView(againBtn)
                    }
                    confirmButtonsRow = confirmRow
                    root.addView(confirmRow)
                }
                else -> {
                    // Default: hint label only, auto-closes on first decode.
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

        ensureCameraXConfigured(requireContext())
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
                provider.bindToLifecycle(viewLifecycleOwner, AVAILABLE_CAMERAS, *useCases.toTypedArray())
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
        if (mediaImage == null || (!multiple && singleResultLocked)) { proxy.close(); return }

        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isEmpty()) return@addOnSuccessListener
                val barcode = barcodes.first()
                val value   = barcode.rawValue ?: return@addOnSuccessListener
                val format  = formatName(barcode.format)

                if (multiple) {
                    if (foundCodes.containsKey(value)) return@addOnSuccessListener
                    foundCodes[value] = format
                    Log.d(TAG, "Scanned (multi): $value ($format) — total ${foundCodes.size}")
                    activity?.runOnUiThread { refreshMultiScanUi() }
                } else {
                    if (singleResultLocked) return@addOnSuccessListener
                    singleResultLocked = true
                    Log.d(TAG, "Scanned: $value ($format)")
                    if (autoClose) {
                        dispatchScanResult(value, format)
                    } else {
                        pendingSingleResult = value to format
                        activity?.runOnUiThread { showSingleConfirmUi(value) }
                    }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    // ─── Multi-scan UI/state ─────────────────────────────────────────────────

    private fun refreshMultiScanUi() {
        val container = foundCodesListContainer ?: return
        container.removeAllViews()
        foundCodes.forEach { (value, format) ->
            val row = TextView(requireContext()).apply {
                text = "• $value ($format)"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 12f
                setPadding(24, 8, 24, 8)
            }
            container.addView(row)
        }
        doneButton?.apply {
            text = "Done (${foundCodes.size})"
            isEnabled = foundCodes.isNotEmpty()
        }
    }

    private fun finishMultiScan() {
        if (foundCodes.isEmpty()) return
        Log.d(TAG, "Multi-scan finished with ${foundCodes.size} code(s)")
        dispatchScanResults(foundCodes.map { it.key to it.value })
    }

    // ─── Single scan, manual-close UI/state ─────────────────────────────────

    private fun showSingleConfirmUi(value: String) {
        scanHintView?.visibility = View.GONE
        confirmTextView?.apply {
            text = "Found: $value"
            visibility = View.VISIBLE
        }
        confirmButtonsRow?.visibility = View.VISIBLE
    }

    private fun confirmSingleResult() {
        val (value, format) = pendingSingleResult ?: return
        dispatchScanResult(value, format)
    }

    private fun resumeScanning() {
        pendingSingleResult = null
        singleResultLocked = false
        confirmTextView?.visibility = View.GONE
        confirmButtonsRow?.visibility = View.GONE
        scanHintView?.visibility = View.VISIBLE
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

    private fun dispatchScanResults(results: List<Pair<String, String>>) {
        dispatch("Blutrixx\\DeviceUtils\\Events\\ScanResults", JSONObject().apply {
            put("results", org.json.JSONArray().apply {
                results.forEach { (value, format) ->
                    put(JSONObject().apply { put("value", value); put("format", format) })
                }
            })
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
