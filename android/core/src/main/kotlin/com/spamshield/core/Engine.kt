package com.spamshield.core

import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.math.min

/** Section 3 of the spec. Thread-safe once constructed. */
class Engine(val rules: Rules, private val classifier: Classifier?) {
    private val keywordRules = rules.sms.keywordRules.map { Triple(it.id, Text.regex(it.pattern), it.weight) }

    fun evaluate(event: Event, context: EngineContext = EngineContext()): Verdict {
        val settings = context.settings
        val cc = settings.countryCode
        val n = Numbers.normalize(event.number, cc)

        if (event.isContact) return Verdict(Action.ALLOW, 0.0, listOf("contact"))
        if (n != null && Patterns.matchesAny(context.allowlist, n, cc)) return Verdict(Action.ALLOW, 0.0, listOf("allowlist"))
        if (n != null && Patterns.matchesAny(context.blocklist, n, cc)) return Verdict(Action.BLOCK, 1.0, listOf("blocklist"))
        if (event.channel == Channel.CALL && n == null && settings.blockHiddenNumbers) {
            return Verdict(Action.BLOCK, 1.0, listOf("hidden_number"))
        }

        val community = communitySignal(n, context)
        val signals = when (event.channel) {
            Channel.CALL -> callSignals(event, n, settings, community)
            Channel.SMS -> smsSignals(event, n, settings, community)
        }

        var keep = 1.0
        for ((_, weight) in signals) keep *= 1.0 - weight
        val score = min(1.0, max(0.0, 1.0 - keep))
        val action = when {
            score >= rules.thresholds.block -> Action.BLOCK
            score >= rules.thresholds.warn -> Action.WARN
            else -> Action.ALLOW
        }
        return Verdict(action, score, signals.map { it.first })
    }

    /** Classifier probability for [body], or `null` when no model is loaded. */
    fun classify(body: String?): Double? = classifier?.probability(body)

    private fun callSignals(event: Event, n: String?, settings: Settings, community: Double?): List<Pair<String, Double>> {
        val call = rules.call
        val signals = mutableListOf<Pair<String, Double>>()
        if (n == null) signals += "hidden_number" to call.hiddenWeight
        if (event.verification == Verification.FAILED) signals += "verification_failed" to call.verificationFailedWeight
        if (n != null && !Numbers.isValidCallerNumber(n)) signals += "invalid_number" to call.invalidNumberWeight
        if (settings.detectNeighborSpoofing && n != null &&
            Numbers.isNeighborSpoof(n, Numbers.normalize(settings.userNumber, settings.countryCode))
        ) {
            signals += "neighbor_spoof" to call.neighborSpoofWeight
        }
        if (community != null) signals += "community" to community
        return signals
    }

    private fun smsSignals(event: Event, n: String?, settings: Settings, community: Double?): List<Pair<String, Double>> {
        val sms = rules.sms
        val body = event.body ?: ""
        val text = Text.lower(body)
        val signals = mutableListOf<Pair<String, Double>>()
        if (community != null) signals += "community" to community
        if (n != null && '@' in n) signals += "sender_email" to sms.senderEmailWeight
        for ((id, pattern, weight) in keywordRules) {
            if (pattern.matcher(text).find()) signals += "keyword:$id" to weight
        }
        signals += urlSignals(text)
        if (settings.useClassifier && classifier != null) {
            val p = classifier.probability(body)
            if (p >= sms.classifier.minProbability) signals += "classifier" to p * sms.classifier.weight
        }
        return signals
    }

    private fun urlSignals(text: String): List<Pair<String, Double>> {
        val urls = rules.sms.urls
        var ip = false
        var short = false
        var tld = false
        for (host in Urls.hosts(text)) {
            if (Urls.isIpHost(host)) ip = true
            if (Urls.isShortener(host, urls.shorteners)) short = true
            if (Urls.tld(host) in urls.suspiciousTlds) tld = true
        }
        return buildList {
            if (ip) add("url_ip_host" to urls.ipHostWeight)
            if (short) add("url_shortener" to urls.shortenerWeight)
            if (tld) add("url_suspicious_tld" to urls.suspiciousTldWeight)
        }
    }

    private fun communitySignal(n: String?, context: EngineContext): Double? {
        if (!context.settings.useCommunity || n == null) return null
        val s = context.community[n] ?: return null
        if (s <= 0) return null
        return min(1.0, s * rules.community.weight)
    }

    companion object {
        const val RULES_RESOURCE = "/spamshield/rules.json"
        const val MODEL_RESOURCE = "/spamshield/model/sms_classifier.json"

        internal val json = Json { ignoreUnknownKeys = true }

        fun parseRules(text: String): Rules = json.decodeFromString(Rules.serializer(), text)

        fun parseModel(text: String): ClassifierModel = json.decodeFromString(ClassifierModel.serializer(), text)

        /** Loads the rules and model bundled as classpath resources (works on the JVM and inside an APK). */
        fun loadBundled(): Engine {
            val rules = parseRules(readResource(RULES_RESOURCE))
            val model = parseModel(readResource(MODEL_RESOURCE))
            return Engine(rules, Classifier(model))
        }

        private fun readResource(path: String): String {
            val stream = Engine::class.java.getResourceAsStream(path)
                ?: error("Missing bundled resource $path")
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
}
