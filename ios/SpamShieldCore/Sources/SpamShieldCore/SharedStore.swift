import Foundation

/// App-level preferences that are not part of detection.
public struct AppPreferences: Codable, Equatable {
    public var backendBaseURL: String
    /// Whether the message filter records flagged senders.
    public var logFlaggedMessages: Bool
    /// Whether the message filter also records message bodies (off by default).
    public var logMessageBodies: Bool
    public var hasCompletedOnboarding: Bool

    public init(
        backendBaseURL: String = CommunityClient.defaultBaseURL,
        logFlaggedMessages: Bool = true,
        logMessageBodies: Bool = false,
        hasCompletedOnboarding: Bool = false
    ) {
        self.backendBaseURL = backendBaseURL
        self.logFlaggedMessages = logFlaggedMessages
        self.logMessageBodies = logMessageBodies
        self.hasCompletedOnboarding = hasCompletedOnboarding
    }

    private enum CodingKeys: String, CodingKey {
        case backendBaseURL, logFlaggedMessages, logMessageBodies, hasCompletedOnboarding
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let defaults = AppPreferences()
        backendBaseURL = try c.decodeIfPresent(String.self, forKey: .backendBaseURL) ?? defaults.backendBaseURL
        logFlaggedMessages = try c.decodeIfPresent(Bool.self, forKey: .logFlaggedMessages) ?? defaults.logFlaggedMessages
        logMessageBodies = try c.decodeIfPresent(Bool.self, forKey: .logMessageBodies) ?? defaults.logMessageBodies
        hasCompletedOnboarding = try c.decodeIfPresent(Bool.self, forKey: .hasCompletedOnboarding)
            ?? defaults.hasCompletedOnboarding
    }
}

/// The locally cached community blocklist.
public struct CommunitySnapshot: Codable, Equatable {
    /// Scores keyed by normalized number.
    public var scores: [String: Double]
    public var etag: String?
    public var generatedAt: String?
    public var lastSync: Date?

    public init(scores: [String: Double] = [:], etag: String? = nil, generatedAt: String? = nil, lastSync: Date? = nil) {
        self.scores = scores
        self.etag = etag
        self.generatedAt = generatedAt
        self.lastSync = lastSync
    }
}

/// A message the filter extension sent to Junk.
public struct FlaggedMessage: Codable, Equatable, Identifiable {
    public var id: UUID
    public var sender: String?
    public var action: Action
    public var score: Double
    public var reasons: [String]
    public var date: Date
    public var body: String?

    public init(
        id: UUID = UUID(),
        sender: String?,
        action: Action,
        score: Double,
        reasons: [String],
        date: Date = Date(),
        body: String? = nil
    ) {
        self.id = id
        self.sender = sender
        self.action = action
        self.score = score
        self.reasons = reasons
        self.date = date
        self.body = body
    }
}

/// Persists user data as JSON files in one directory: the App Group container
/// on device (shared by the app and both extensions), a temp dir in tests.
/// Reads never throw: a missing or unreadable file yields the default value.
public final class SharedStore {
    public static let flaggedLogLimit = 200

    public let directory: URL

    private enum File: String {
        case blocklist = "blocklist.json"
        case allowlist = "allowlist.json"
        case settings = "settings.json"
        case preferences = "preferences.json"
        case community = "community.json"
        case installId = "install.json"
        case flagged = "flagged-messages.json"
    }

    private struct InstallRecord: Codable {
        var installId: String
    }

    public init(directory: URL) {
        self.directory = directory
    }

    #if canImport(Darwin)
    /// The store in the shared App Group container, or `nil` when the
    /// entitlement is missing.
    public static func appGroup(_ identifier: String = AppIdentifiers.appGroup) -> SharedStore? {
        guard let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: identifier) else {
            return nil
        }
        return SharedStore(directory: url.appendingPathComponent("SpamShield", isDirectory: true))
    }
    #endif

    // MARK: Lists and settings

    public func blocklist() -> [String] { read([String].self, .blocklist) ?? [] }
    public func setBlocklist(_ entries: [String]) throws { try write(entries, .blocklist) }

    public func allowlist() -> [String] { read([String].self, .allowlist) ?? [] }
    public func setAllowlist(_ entries: [String]) throws { try write(entries, .allowlist) }

    public func settings() -> Settings { read(Settings.self, .settings) ?? Settings() }
    public func setSettings(_ settings: Settings) throws { try write(settings, .settings) }

    public func preferences() -> AppPreferences { read(AppPreferences.self, .preferences) ?? AppPreferences() }
    public func setPreferences(_ preferences: AppPreferences) throws { try write(preferences, .preferences) }

    // MARK: Community cache

    public func community() -> CommunitySnapshot { read(CommunitySnapshot.self, .community) ?? CommunitySnapshot() }
    public func setCommunity(_ snapshot: CommunitySnapshot) throws { try write(snapshot, .community) }

    // MARK: Install id

    /// A random UUID generated on first use and kept for the life of the install.
    public func installId() -> String {
        if let record = read(InstallRecord.self, .installId), !record.installId.isEmpty {
            return record.installId
        }
        let id = UUID().uuidString
        try? write(InstallRecord(installId: id), .installId)
        return id
    }

    // MARK: Flagged-message log

    /// Newest last.
    public func flaggedMessages() -> [FlaggedMessage] { read([FlaggedMessage].self, .flagged) ?? [] }

    public func appendFlaggedMessage(_ message: FlaggedMessage, limit: Int = SharedStore.flaggedLogLimit) throws {
        var log = flaggedMessages()
        log.append(message)
        if log.count > limit {
            log.removeFirst(log.count - limit)
        }
        try write(log, .flagged)
    }

    public func clearFlaggedMessages() throws { try write([FlaggedMessage](), .flagged) }

    // MARK: Detection

    /// The detection context built from everything stored.
    public func context() -> DetectionContext {
        DetectionContext(
            blocklist: blocklist(),
            allowlist: allowlist(),
            community: community().scores,
            settings: settings()
        )
    }

    // MARK: Files

    private func url(_ file: File) -> URL {
        directory.appendingPathComponent(file.rawValue, isDirectory: false)
    }

    private func read<T: Decodable>(_ type: T.Type, _ file: File) -> T? {
        guard let data = try? Data(contentsOf: url(file)) else { return nil }
        return try? SharedStore.decoder().decode(type, from: data)
    }

    private func write<T: Encodable>(_ value: T, _ file: File) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
        let data = try SharedStore.encoder().encode(value)
        try data.write(to: url(file), options: [.atomic])
    }

    private static func encoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }

    private static func decoder() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }
}
