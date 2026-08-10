package com.blutrixx.device.utils

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONObject

class FilePickerFragment : Fragment() {

    companion object {
        private const val TAG = "FilePickerFragment"
        private const val ARG_MIME_TYPE = "mimeType"

        fun newInstance(mimeType: String) = FilePickerFragment().apply {
            arguments = Bundle().apply { putString(ARG_MIME_TYPE, mimeType) }
        }
    }

    private var launched = false

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val mimeType = arguments?.getString(ARG_MIME_TYPE) ?: "application/pdf"
        val host = activity ?: return@registerForActivityResult

        if (uri != null) {
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not persist URI permission: ${e.message}")
            }

            val (filename, size) = queryUriMetadata(uri)
            Log.d(TAG, "File selected: $filename ($size bytes)")

            NativeActionCoordinator.dispatchEvent(
                host,
                "Blutrixx\\DeviceUtils\\Events\\FileSelected",
                JSONObject().apply {
                    put("uri", uri.toString())
                    put("filename", filename)
                    put("size", size)
                    put("mimeType", mimeType)
                }.toString()
            )
        } else {
            Log.d(TAG, "File picker cancelled by user")
            NativeActionCoordinator.dispatchEvent(
                host,
                "Blutrixx\\DeviceUtils\\Events\\FilePickCancelled",
                JSONObject().toString()
            )
        }

        parentFragmentManager.beginTransaction().remove(this).commitAllowingStateLoss()
    }

    override fun onStart() {
        super.onStart()
        if (!launched) {
            launched = true
            val mimeType = arguments?.getString(ARG_MIME_TYPE) ?: "application/pdf"
            pickFileLauncher.launch(arrayOf(mimeType))
        }
    }

    private fun queryUriMetadata(uri: Uri): Pair<String, Long> {
        return try {
            val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIdx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = it.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIdx >= 0) it.getString(nameIdx) ?: "document.pdf" else "document.pdf"
                    val size = if (sizeIdx >= 0) it.getLong(sizeIdx) else 0L
                    Pair(name, size)
                } else Pair("document.pdf", 0L)
            } ?: Pair("document.pdf", 0L)
        } catch (e: Exception) {
            Log.w(TAG, "queryUriMetadata failed: ${e.message}")
            Pair("document.pdf", 0L)
        }
    }
}
