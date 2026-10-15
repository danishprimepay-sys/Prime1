import Foundation

public enum SpamShieldError: Error, Equatable {
    case missingResource(String)
}

/// The detection engine (SPEC §3). Construct once and reuse: it holds
/// compiled regular expressions.
public final class SpamEngine {
    public let rules: Rules
    public let classifier: SMSClassifier?
    private let keywordRules: [CompiledKeywordRule]

    private struct CompiledKeywordRule {
        let id: String
        let regex: PortableRegex
        let weight: Double
    }

    private struct Signal {
        let id: String
        let weight: Double
    }

    public init(rules: Rules, classifier: SMSClassifier?) throws {
        self.rules = rules
        self.classifier = classifier
        var compiled: [CompiledKeywordRule] = []
        for rule in rules.sms.keywordRules {
            compiled.append(CompiledKeywordRule(id: rule.id, regex: try PortableRegex(rule.pattern), weight: rule.weight))
        }
        keywordRules = compiled
    }

    // MARK: Loading

    /// An engine using the rules and classifier bundled with this package.
    public static func bundled() throws -> SpamEngine {
        let rules = try JSONDecoder().decode(Rules.self, from: bundledResource("rules"))
        let model = try JSONDecoder().decode(ClassifierModel.self, from: bundledResource("sms_classifier"))
        return try SpamEngine(rules: rules, classifier: SMSClassifier(model: model))
    }

    /// The bundled `rules.json`.
    public static func bundledRules() throws -> Rules {
        try JSONDecoder().decode(Rules.self, from: bundledResource("rules"))
    }

    private static func bundledResource(_ name: String) throws -> Data {
        guard let url = Bundle.module.url(forResource: name, withExtension: "json") else {
            throw SpamShieldError.missingResource(name + ".json")
        }
        return try Data(contentsOf: url)
    }

    // MARK: Evaluation

    public func evaluate(_ event: Event, context: DetectionContext = DetectionContext()) -> Verdict {
        let settings = context.settings
        let cc = settings.countryCode
        let n = PhoneNumbers.normalize(event.number, countryCode: cc)

        if event.isContact {
            return Verdict(action: .allow, score: 0, reasons: ["contact"])
        }
        if let n = n, NumberPattern.matchesAny(context.allowlist, number: n, countryCode: cc) {
            return Verdict(action: .allow, score: 0, reasons: ["allowlist"])
        }
        if let n = n, NumberPattern.matchesAny(context.blocklist, number: n, countryCode: cc) {
            return Verdict(action: .block, score: 1, reasons: ["blocklist"])
        }
        if event.channel == .call && n == nil && settings.blockHiddenNumbers {
            return Verdict(action: .block, score: 1, reasons: ["hidden_number"])
        }

        let community = communityWeight(n, context: context)
        let signals: [Signal]
        switch event.channel {
        case .call:
            signals = callSignals(event, number: n, settings: settings, community: community)
        case .sms:
            signals = smsSignals(event, number: n, settings: settings, community: community)
        }

        var keep = 1.0
        for signal in signals {
            keep *= 1.0 - signal.weight
        }
        let score = min(1.0, max(0.0, 1.0 - keep))
        let action: Action
        if score >= rules.thresholds.block {
            action = .block
        } else if score >= rules.thresholds.warn {
            action = .warn
        } else {
            action = .allow
        }
        return Verdict(action: action, score: score, reasons: signals.map { $0.id })
    }

    private func callSignals(_ event: Event, number n: String?, settings: Settings, community: Double?) -> [Signal] {
        let call = rules.call
        var signals: [Signal] = []
        if n == nil {
            signals.append(Signal(id: "hidden_number", weight: call.hiddenWeight))
        }
        if event.verification == .failed {
            signals.append(Signal(id: "verification_failed", weight: call.verificationFailedWeight))
        }
        if let n = n, !PhoneNumbers.isValidCallerNumber(n) {
            signals.append(Signal(id: "invalid_number", weight: call.invalidNumberWeight))
        }
        if settings.detectNeighborSpoofing, let n = n {
            let user = PhoneNumbers.normalize(settings.userNumber, countryCode: settings.countryCode)
            if PhoneNumbers.isNeighborSpoof(n, user) {
                signals.append(Signal(id: "neighbor_spoof", weight: call.neighborSpoofWeight))
            }
        }
        if let community = community {
            signals.append(Signal(id: "community", weight: community))
        }
        return signals
    }

    private func smsSignals(_ event: Event, number n: String?, settings: Settings, community: Double?) -> [Signal] {
        let sms = rules.sms
        let body = event.body ?? ""
        let text = body.lowercased()
        var signals: [Signal] = []
        if let community = community {
            signals.append(Signal(id: "community", weight: community))
        }
        if let n = n, n.contains("@") {
            signals.append(Signal(id: "sender_email", weight: sms.senderEmailWeight))
        }
        for rule in keywordRules where rule.regex.find(in: text) {
            signals.append(Signal(id: "keyword:" + rule.id, weight: rule.weight))
        }
        signals.append(contentsOf: urlSignals(text))
        if settings.useClassifier, let classifier = classifier {
            let p = classifier.probability(body)
            if p >= sms.classifier.minProbability {
                signals.append(Signal(id: "classifier", weight: p * sms.classifier.weight))
            }
        }
        return signals
    }

    private func communityWeight(_ n: String?, context: DetectionContext) -> Double? {
        guard context.settings.useCommunity, let n = n, let s = context.community[n], s > 0 else {
            return nil
        }
        return min(1.0, s * rules.community.weight)
    }

    private func urlSignals(_ text: String) -> [Signal] {
        let urls = rules.sms.urls
        var ip = false
        var shortener = false
        var tld = false
        for host in URLHosts.hosts(in: text) {
            if URLHosts.isIPHost(host) {
                ip = true
            }
            if URLHosts.isShortener(host, shorteners: urls.shorteners) {
                shortener = true
            }
            if urls.suspiciousTlds.contains(URLHosts.topLevelDomain(host)) {
                tld = true
            }
        }
        var signals: [Signal] = []
        if ip {
            signals.append(Signal(id: "url_ip_host", weight: urls.ipHostWeight))
        }
        if shortener {
            signals.append(Signal(id: "url_shortener", weight: urls.shortenerWeight))
        }
        if tld {
            signals.append(Signal(id: "url_suspicious_tld", weight: urls.suspiciousTldWeight))
        }
        return signals
    }
}
