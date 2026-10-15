package com.spamshield.android.data

import com.spamshield.core.Action

enum class ListKind(val key: String) {
    BLOCK("block"),
    ALLOW("allow"),
}

data class ListEntry(val id: Long, val kind: ListKind, val pattern: String, val addedAt: Long)

data class CallLogEntry(
    val id: Long,
    /** Raw caller ID as delivered by the platform; `null` for a hidden caller. */
    val number: String?,
    val at: Long,
    val action: Action,
    val score: Double,
    val reasons: List<String>,
)

/** An SMS as received from the radio, before it is filed anywhere. */
data class IncomingSms(
    val address: String?,
    val body: String,
    val sentAt: Long,
    val receivedAt: Long,
    val subscriptionId: Int,
)

data class QuarantinedSms(
    val id: Long,
    val sms: IncomingSms,
    val score: Double,
    val reasons: List<String>,
)
