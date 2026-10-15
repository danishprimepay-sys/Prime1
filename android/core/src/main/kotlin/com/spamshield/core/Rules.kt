package com.spamshield.core

import kotlinx.serialization.Serializable

/** Typed view of `shared/rules.json`. */
@Serializable
data class Rules(
    val version: Int,
    val thresholds: Thresholds,
    val community: CommunityRules,
    val call: CallRules,
    val sms: SmsRules,
) {
    @Serializable
    data class Thresholds(val block: Double, val warn: Double)

    @Serializable
    data class CommunityRules(val weight: Double)

    @Serializable
    data class CallRules(
        val hiddenWeight: Double,
        val verificationFailedWeight: Double,
        val invalidNumberWeight: Double,
        val neighborSpoofWeight: Double,
    )

    @Serializable
    data class SmsRules(
        val senderEmailWeight: Double,
        val classifier: ClassifierRules,
        val keywordRules: List<KeywordRule>,
        val urls: UrlRules,
    )

    @Serializable
    data class ClassifierRules(val minProbability: Double, val weight: Double)

    @Serializable
    data class KeywordRule(val id: String, val pattern: String, val weight: Double)

    @Serializable
    data class UrlRules(
        val shortenerWeight: Double,
        val ipHostWeight: Double,
        val suspiciousTldWeight: Double,
        val shorteners: List<String>,
        val suspiciousTlds: List<String>,
    )
}

/** Typed view of `shared/model/sms_classifier.json`. */
@Serializable
data class ClassifierModel(
    val version: Int,
    val bias: Double,
    val tokenizer: Tokenizer,
    val weights: Map<String, Double>,
) {
    @Serializable
    data class Tokenizer(val replacements: List<Replacement>, val minTokenLength: Int)

    @Serializable
    data class Replacement(val pattern: String, val token: String)
}
