import Foundation

/// `GET /v1/blocklist` response.
public struct BlocklistResponse: Codable, Equatable {
    public struct Entry: Codable, Equatable {
        public var number: String
        public var score: Double

        public init(number: String, score: Double) {
            self.number = number
            self.score = score
        }
    }

    public var generatedAt: String
    public var minScore: Double
    public var entries: [Entry]

    public init(generatedAt: String, minScore: Double, entries: [Entry]) {
        self.generatedAt = generatedAt
        self.minScore = minScore
        self.entries = entries
    }
}

public enum ReportVerdict: String, Codable, CaseIterable {
    case spam
    case notSpam = "not_spam"
}

/// `POST /v1/reports` body.
public struct ReportRequest: Codable, Equatable {
    public var number: String
    public var channel: Channel
    public var verdict: ReportVerdict
    public var category: String?
    public var installId: String

    public init(number: String, channel: Channel, verdict: ReportVerdict, category: String? = nil, installId: String) {
        self.number = number
        self.channel = channel
        self.verdict = verdict
        self.category = category
        self.installId = installId
    }

    private enum CodingKeys: String, CodingKey {
        case number, channel, verdict, category, installId
    }

    // Omit `category` entirely when nil rather than sending null.
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(number, forKey: .number)
        try c.encode(channel, forKey: .channel)
        try c.encode(verdict, forKey: .verdict)
        try c.encodeIfPresent(category, forKey: .category)
        try c.encode(installId, forKey: .installId)
    }
}

/// `POST /v1/reports` 202 response.
public struct ReportResponse: Codable, Equatable {
    public var accepted: Bool
    public var number: String

    public init(accepted: Bool, number: String) {
        self.accepted = accepted
        self.number = number
    }
}

/// `GET /v1/numbers/{number}` response.
public struct NumberLookup: Codable, Equatable {
    public var number: String
    public var score: Double
    public var spamReports: Int
    public var notSpamReports: Int
    public var lastReportedAt: String?

    public init(number: String, score: Double, spamReports: Int, notSpamReports: Int, lastReportedAt: String?) {
        self.number = number
        self.score = score
        self.spamReports = spamReports
        self.notSpamReports = notSpamReports
        self.lastReportedAt = lastReportedAt
    }
}
