package com.spamshield.android.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.PhoneLookup
import android.util.Log

/** Phone-number lookups in the user's contacts; all return "unknown" without READ_CONTACTS. */
object Contacts {
    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun isContact(context: Context, number: String?): Boolean = displayName(context, number) != null

    fun displayName(context: Context, number: String?): String? {
        if (number.isNullOrBlank() || !hasPermission(context)) return null
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return try {
            context.contentResolver.query(uri, arrayOf(PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) ?: number else null
            }
        } catch (e: RuntimeException) {
            Log.w("SpamShield", "Contact lookup failed", e)
            null
        }
    }
}
