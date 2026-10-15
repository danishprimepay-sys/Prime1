package com.spamshield.android.sms

import android.net.Uri

/** Parses `sms:`, `smsto:`, `mms:` and `mmsto:` URIs as well as user-typed recipient lists. */
object Recipients {
    fun fromUri(uri: Uri?): List<String> {
        val part = uri?.schemeSpecificPart ?: return emptyList()
        return parse(part.substringBefore('?'))
    }

    fun parse(text: String): List<String> =
        text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** The `body` query parameter of an sms: URI, if present. */
    fun bodyFromUri(uri: Uri?): String? {
        val part = uri?.schemeSpecificPart ?: return null
        val query = part.substringAfter('?', "").ifEmpty { return null }
        return query.split('&').firstOrNull { it.startsWith("body=") }?.removePrefix("body=")?.let(Uri::decode)
    }
}
