package com.spamshield.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Channel {
    @SerialName("call") CALL,
    @SerialName("sms") SMS,
}

/** STIR/SHAKEN verification result reported by the platform (calls only). */
@Serializable
enum class Verification {
    @SerialName("passed") PASSED,
    @SerialName("failed") FAILED,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
enum class Action { ALLOW, WARN, BLOCK }

@Serializable
data class Event(
    val channel: Channel,
    /** Raw caller ID or SMS sender; `null` for a hidden caller. */
    val number: String? = null,
    val body: String? = null,
    val isContact: Boolean = false,
    val verification: Verification = Verification.UNKNOWN,
)

@Serializable
data class Settings(
    val blockHiddenNumbers: Boolean = false,
    val detectNeighborSpoofing: Boolean = true,
    /** The user's own number, raw; normalized on use. */
    val userNumber: String? = null,
    val useClassifier: Boolean = true,
    val useCommunity: Boolean = true,
    val countryCode: String = Numbers.DEFAULT_COUNTRY_CODE,
)

/** Everything [Engine.evaluate] needs besides the event. Named to avoid clashing with android.content.Context. */
@Serializable
data class EngineContext(
    val blocklist: List<String> = emptyList(),
    val allowlist: List<String> = emptyList(),
    /** Community spam score (0..1) keyed by normalized number. May contain only the numbers of interest. */
    val community: Map<String, Double> = emptyMap(),
    val settings: Settings = Settings(),
)

@Serializable
data class Verdict(val action: Action, val score: Double, val reasons: List<String>)
