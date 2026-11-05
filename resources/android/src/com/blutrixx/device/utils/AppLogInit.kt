package com.blutrixx.device.utils

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Not a real content provider: Android creates every provider when the app's process starts, before anything else of the app runs, which makes
 * this the earliest place to start the log and install the crash handler. It serves no data.
 */
class AppLogInit : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { AppLog.init(it) }
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
