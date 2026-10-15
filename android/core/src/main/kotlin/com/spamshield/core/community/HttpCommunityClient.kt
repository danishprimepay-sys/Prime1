package com.spamshield.core.community

import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** [CommunityClient] over `java.net.HttpURLConnection` (available on both the JVM and Android). */
class HttpCommunityClient(
    baseUrl: String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) : CommunityClient {
    private val base = baseUrl.trim().trimEnd('/')

    override fun fetchBlocklist(minScore: Double, etag: String?): BlocklistResult =
        request("GET", "/v1/blocklist?minScore=$minScore", headers = etag?.let { mapOf("If-None-Match" to it) }.orEmpty()) { conn ->
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> BlocklistResult.NotModified
                HttpURLConnection.HTTP_OK ->
                    BlocklistResult.Updated(json.decodeFromString(Blocklist.serializer(), conn.readBody()), conn.getHeaderField("ETag"))
                else -> throw CommunityException("GET blocklist failed: HTTP $code")
            }
        }

    override fun report(report: Report) {
        val body = json.encodeToString(Report.serializer(), report)
        request("POST", "/v1/reports", body = body) { conn ->
            val code = conn.responseCode
            if (code !in 200..299) throw CommunityException("POST report failed: HTTP $code")
        }
    }

    override fun lookup(number: String): NumberInfo {
        val path = "/v1/numbers/" + URLEncoder.encode(number, "UTF-8").replace("+", "%20")
        return request("GET", path) { conn ->
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw CommunityException("Lookup failed: HTTP $code")
            json.decodeFromString(NumberInfo.serializer(), conn.readBody())
        }
    }

    private fun <T> request(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        handle: (HttpURLConnection) -> T,
    ): T {
        val conn = try {
            URI(base + path).toURL().openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw CommunityException("Invalid server URL: $base", e)
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach(conn::setRequestProperty)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            return handle(conn)
        } catch (e: IOException) {
            throw CommunityException("Network error: ${e.message}", e)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw CommunityException("Unexpected response from server", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun HttpURLConnection.readBody(): String = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}
