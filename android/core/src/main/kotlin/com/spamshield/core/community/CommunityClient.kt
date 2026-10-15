package com.spamshield.core.community

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Client for the SpamShield community backend. Implementations do blocking I/O; call off the main thread. */
interface CommunityClient {
    /** Fetches the community blocklist; returns [BlocklistResult.NotModified] when [etag] is still current. */
    fun fetchBlocklist(minScore: Double, etag: String?): BlocklistResult

    fun report(report: Report)

    fun lookup(number: String): NumberInfo
}

sealed interface BlocklistResult {
    data object NotModified : BlocklistResult
    data class Updated(val blocklist: Blocklist, val etag: String?) : BlocklistResult
}

@Serializable
data class Blocklist(
    val generatedAt: String,
    val minScore: Double,
    val entries: List<Entry>,
) {
    @Serializable
    data class Entry(val number: String, val score: Double)
}

@Serializable
enum class ReportChannel {
    @SerialName("call") CALL,
    @SerialName("sms") SMS,
}

@Serializable
enum class ReportVerdict {
    @SerialName("spam") SPAM,
    @SerialName("not_spam") NOT_SPAM,
}

@Serializable
data class Report(
    val number: String,
    val channel: ReportChannel,
    val verdict: ReportVerdict,
    val installId: String,
    val category: String? = null,
)

@Serializable
data class NumberInfo(
    val number: String,
    val score: Double,
    val spamReports: Int = 0,
    val notSpamReports: Int = 0,
    val lastReportedAt: String? = null,
)

class CommunityException(message: String, cause: Throwable? = null) : Exception(message, cause)
