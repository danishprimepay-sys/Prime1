import Foundation

public enum Channel: String, Codable, CaseIterable {
    case call
    case sms
}

/// STIR/SHAKEN result for calls.
public enum Verification: String, Codable, CaseIterable {
    case passed
    case failed
    case unknown
}

public struct Event: Codable, Equatable {
    public var channel: Channel
    /// Raw caller ID / sender.
    public var number: String?
    /// SMS body.
    public var body: String?
    public var isContact: Bool
    public var verification: Verification

    public init(
        channel: Channel,
        number: String?,
        body: String? = nil,
        isContact: Bool = false,
        verification: Verification = .unknown
    ) {
        self.channel = channel
        self.number = number
        self.body = body
        self.isContact = isContact
        self.verification = verification
    }

    private enum CodingKeys: String, CodingKey {
        case channel, number, body, isContact, verification
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        channel = try c.decode(Channel.self, forKey: .channel)
        number = try c.decodeIfPresent(String.self, forKey: .number)
        body = try c.decodeIfPresent(String.self, forKey: .body)
        isContact = try c.decodeIfPresent(Bool.self, forKey: .isContact) ?? false
        verification = try c.decodeIfPresent(Verification.self, forKey: .verification) ?? .unknown
    }
}

/// User-controlled detection settings. Missing keys decode to the spec defaults.
public struct Settings: Codable, Equatable {
    public var blockHiddenNumbers: Bool
    public var detectNeighborSpoofing: Bool
    /// The user's own number, raw; normalized on use.
    public var userNumber: String?
    public var useClassifier: Bool
    public var useCommunity: Bool
    public var countryCode: String

    public init(
        blockHiddenNumbers: Bool = false,
        detectNeighborSpoofing: Bool = true,
        userNumber: String? = nil,
        useClassifier: Bool = true,
        useCommunity: Bool = true,
        countryCode: String = "1"
    ) {
        self.blockHiddenNumbers = blockHiddenNumbers
        self.detectNeighborSpoofing = detectNeighborSpoofing
        self.userNumber = userNumber
        self.useClassifier = useClassifier
        self.useCommunity = useCommunity
        self.countryCode = countryCode
    }

    private enum CodingKeys: String, CodingKey {
        case blockHiddenNumbers, detectNeighborSpoofing, userNumber, useClassifier, useCommunity, countryCode
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let defaults = Settings()
        blockHiddenNumbers = try c.decodeIfPresent(Bool.self, forKey: .blockHiddenNumbers) ?? defaults.blockHiddenNumbers
        detectNeighborSpoofing = try c.decodeIfPresent(Bool.self, forKey: .detectNeighborSpoofing)
            ?? defaults.detectNeighborSpoofing
        userNumber = try c.decodeIfPresent(String.self, forKey: .userNumber)
        useClassifier = try c.decodeIfPresent(Bool.self, forKey: .useClassifier) ?? defaults.useClassifier
        useCommunity = try c.decodeIfPresent(Bool.self, forKey: .useCommunity) ?? defaults.useCommunity
        countryCode = try c.decodeIfPresent(String.self, forKey: .countryCode) ?? defaults.countryCode
    }
}

/// Everything besides the event that detection depends on.
public struct DetectionContext: Codable, Equatable {
    public var blocklist: [String]
    public var allowlist: [String]
    /// Community scores (0...1) keyed by normalized number.
    public var community: [String: Double]
    public var settings: Settings

    public init(
        blocklist: [String] = [],
        allowlist: [String] = [],
        community: [String: Double] = [:],
        settings: Settings = Settings()
    ) {
        self.blocklist = blocklist
        self.allowlist = allowlist
        self.community = community
        self.settings = settings
    }

    private enum CodingKeys: String, CodingKey {
        case blocklist, allowlist, community, settings
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        blocklist = try c.decodeIfPresent([String].self, forKey: .blocklist) ?? []
        allowlist = try c.decodeIfPresent([String].self, forKey: .allowlist) ?? []
        community = try c.decodeIfPresent([String: Double].self, forKey: .community) ?? [:]
        settings = try c.decodeIfPresent(Settings.self, forKey: .settings) ?? Settings()
    }
}

public enum Action: String, Codable, CaseIterable {
    case allow = "ALLOW"
    case warn = "WARN"
    case block = "BLOCK"
}

public struct Verdict: Codable, Equatable {
    public var action: Action
    /// Combined spam score in 0...1.
    public var score: Double
    /// Signal ids in collection order.
    public var reasons: [String]

    public init(action: Action, score: Double, reasons: [String]) {
        self.action = action
        self.score = score
        self.reasons = reasons
    }
}
