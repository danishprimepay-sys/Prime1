package com.spamshield.core

/** Section 4 of the spec: hosts of URLs found in (lowercased) message text. */
object Urls {
    const val URL_PATTERN =
        "(https?://)?(([a-z0-9-]+[.])+[a-z]{2,24}|[0-9]{1,3}([.][0-9]{1,3}){3})(?![a-z0-9-])"

    private val url = Text.regex(URL_PATTERN)
    private val ipHost = Text.regex("^[0-9]{1,3}([.][0-9]{1,3}){3}$")

    fun hosts(text: String): List<String> {
        val m = url.matcher(text)
        val out = mutableListOf<String>()
        while (m.find()) out += m.group(2)
        return out
    }

    fun isIpHost(host: String): Boolean = ipHost.matcher(host).find()

    fun isShortener(host: String, shorteners: List<String>): Boolean =
        shorteners.any { host == it || host.endsWith(".$it") }

    fun tld(host: String): String = host.substringAfterLast('.')
}
