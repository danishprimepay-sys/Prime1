package com.spamshield.core.community

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpCommunityClientTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val requests = mutableListOf<String>()
    private val client by lazy { HttpCommunityClient("http://127.0.0.1:${server.address.port}/") }

    init {
        server.createContext("/v1/blocklist") { ex ->
            requests += "${ex.requestMethod} ${ex.requestURI} etag=${ex.requestHeaders.getFirst("If-None-Match")}"
            if (ex.requestHeaders.getFirst("If-None-Match") == "\"v1\"") {
                ex.sendResponseHeaders(304, -1)
            } else {
                val body = """{"generatedAt":"2026-01-01T00:00:00Z","minScore":0.5,"entries":[{"number":"+12025550123","score":0.93}],"extra":1}"""
                ex.responseHeaders.add("ETag", "\"v1\"")
                ex.sendResponseHeaders(200, body.length.toLong())
                ex.responseBody.use { it.write(body.toByteArray()) }
            }
        }
        server.createContext("/v1/reports") { ex ->
            requests += "${ex.requestMethod} ${ex.requestURI} " + ex.requestBody.readBytes().decodeToString()
            ex.sendResponseHeaders(202, -1)
        }
        server.createContext("/v1/numbers/") { ex ->
            requests += "${ex.requestMethod} ${ex.requestURI.rawPath}"
            val body = """{"number":"+12025550123","score":0.4,"spamReports":3,"notSpamReports":1,"lastReportedAt":null}"""
            ex.sendResponseHeaders(200, body.length.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun blocklistHonoursEtag() {
        val first = assertIs<BlocklistResult.Updated>(client.fetchBlocklist(0.5, null))
        assertEquals("\"v1\"", first.etag)
        assertEquals(0.93, first.blocklist.entries.single().score)
        assertIs<BlocklistResult.NotModified>(client.fetchBlocklist(0.5, first.etag))
        assertEquals("GET /v1/blocklist?minScore=0.5 etag=null", requests[0])
    }

    @Test
    fun reportPostsJson() {
        client.report(Report("+12025550123", ReportChannel.SMS, ReportVerdict.NOT_SPAM, "abc"))
        assertEquals(
            """POST /v1/reports {"number":"+12025550123","channel":"sms","verdict":"not_spam","installId":"abc"}""",
            requests.single(),
        )
    }

    @Test
    fun lookupEncodesNumber() {
        val info = client.lookup("+12025550123")
        assertEquals(3, info.spamReports)
        assertTrue(requests.single().endsWith("/v1/numbers/%2B12025550123"))
    }

    @Test
    fun networkFailureIsWrapped() {
        assertFailsWith<CommunityException> { HttpCommunityClient("http://127.0.0.1:1").report(Report("1", ReportChannel.CALL, ReportVerdict.SPAM, "x")) }
    }
}
