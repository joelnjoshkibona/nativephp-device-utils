package com.blutrixx.device.utils

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONObject

/**
 * Requests one or more dangerous Android runtime permissions via the system
 * multi-permission dialog, mirroring FilePickerFragment's pattern: a headless
 * fragment that launches an ActivityResultContract as soon as it starts and
 * reports the outcome back to PHP as a NativePHP event, then removes itself.
 *
 * Permissions already granted are filtered out before the dialog is shown —
 * if nothing needs asking, the result event fires immediately with no dialog
 * at all (e.g. the caller re-requests permissions it already has).
 */
class PermissionRequestFragment : Fragment() {

    companion object {
        private const val TAG = "PermissionRequestFragment"
        private const val ARG_PERMISSIONS = "permissions"
        private const val ARG_ID = "id"

        fun newInstance(permissions: Array<String>, id: String?) = PermissionRequestFragment().apply {
            arguments = Bundle().apply {
                putStringArray(ARG_PERMISSIONS, permissions)
                putString(ARG_ID, id)
            }
        }
    }

    private var launched = false

    // Permissions that were already granted when this fragment started — merged
    // back into the result map so the caller gets a status for every permission
    // it asked for, not just the ones that actually needed a system dialog.
    private var alreadyGranted: List<String> = emptyList()

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val host = activity ?: return@registerForActivityResult

        val merged = mutableMapOf<String, Boolean>()
        alreadyGranted.forEach { merged[it] = true }
        results.forEach { (permission, granted) -> merged[permission] = granted }

        dispatchResult(host, merged)
    }

    override fun onStart() {
        super.onStart()
        if (launched) return
        launched = true

        val requested = arguments?.getStringArray(ARG_PERMISSIONS) ?: emptyArray()
        val context = requireContext()

        val granted = mutableListOf<String>()
        val needed = mutableListOf<String>()
        requested.forEach { permission ->
            val state = ContextCompat.checkSelfPermission(context, permission)
            if (state == PackageManager.PERMISSION_GRANTED) {
                granted.add(permission)
            } else {
                needed.add(permission)
            }
        }
        alreadyGranted = granted

        if (needed.isEmpty()) {
            Log.d(TAG, "All requested permissions already granted, skipping dialog")
            val host = activity ?: return
            dispatchResult(host, granted.associateWith { true })
            return
        }

        Log.d(TAG, "Requesting permissions: $needed")
        requestPermissionsLauncher.launch(needed.toTypedArray())
    }

    private fun dispatchResult(host: FragmentActivity, results: Map<String, Boolean>) {
        val id = arguments?.getString(ARG_ID)
        Log.d(TAG, "Permission results: $results (id=$id)")

        NativeActionCoordinator.dispatchEvent(
            host,
            "Blutrixx\\DeviceUtils\\Events\\PermissionsResult",
            JSONObject().apply {
                put("results", JSONObject(results))
                if (id != null) put("id", id)
            }.toString()
        )

        parentFragmentManager.beginTransaction().remove(this).commitAllowingStateLoss()
    }
}
