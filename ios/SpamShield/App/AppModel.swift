import CallKit
import Combine
import Foundation
import SpamShieldCore

/// App state backed by the shared store. Every edit is written straight to
/// the App Group container so the extensions see it.
@MainActor
final class AppModel: ObservableObject {
    enum CallDirectoryStatus: Equatable {
        case unknown
        case enabled
        case disabled
        case failed(String)
    }

    let store: SharedStore
    /// False when the App Group entitlement is missing (e.g. unsigned builds):
    /// the app then works on private storage the extensions cannot read.
    let usesAppGroup: Bool
    let engine: SpamEngine?

    @Published var blocklist: [String] {
        didSet { persist { try $0.setBlocklist(blocklist) } }
    }
    @Published var allowlist: [String] {
        didSet { persist { try $0.setAllowlist(allowlist) } }
    }
    @Published var settings: Settings {
        didSet { persist { try $0.setSettings(settings) } }
    }
    @Published var preferences: AppPreferences {
        didSet { persist { try $0.setPreferences(preferences) } }
    }
    @Published private(set) var community: CommunitySnapshot
    @Published private(set) var flaggedMessages: [FlaggedMessage] = []
    @Published private(set) var callDirectoryStatus: CallDirectoryStatus = .unknown
    @Published private(set) var callDirectoryCounts = (blocking: 0, identification: 0)
    @Published private(set) var isSyncing = false
    @Published var errorMessage: String?

    init() {
        let shared = SharedStore.appGroup()
        let store = shared ?? AppModel.privateStore()
        self.store = store
        usesAppGroup = shared != nil
        engine = try? SpamEngine.bundled()
        blocklist = store.blocklist()
        allowlist = store.allowlist()
        settings = store.settings()
        preferences = store.preferences()
        community = store.community()
        refreshFromStore()
    }

    private static func privateStore() -> SharedStore {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return SharedStore(directory: base.appendingPathComponent("SpamShield", isDirectory: true))
    }

    private func persist(_ write: (SharedStore) throws -> Void) {
        do {
            try write(store)
        } catch {
            errorMessage = "Could not save: \(error.localizedDescription)"
        }
    }

    var thresholds: Rules.Thresholds {
        engine?.rules.thresholds ?? Rules.Thresholds(block: 0.85, warn: 0.55)
    }

    var context: DetectionContext {
        DetectionContext(blocklist: blocklist, allowlist: allowlist, community: community.scores, settings: settings)
    }

    /// Normalizes user input the same way the engine does, falling back to the trimmed text.
    func normalized(_ raw: String) -> String {
        PhoneNumbers.normalize(raw, countryCode: settings.countryCode)
            ?? raw.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Picks up changes the extensions made (e.g. the flagged-message log).
    func refreshFromStore() {
        community = store.community()
        flaggedMessages = Array(store.flaggedMessages().reversed())
        let entries = CallDirectoryBuilder.build(store: store, thresholds: thresholds)
        callDirectoryCounts = (blocking: entries.blocking.count, identification: entries.identification.count)
    }

    func clearFlaggedMessages() {
        persist { try $0.clearFlaggedMessages() }
        refreshFromStore()
    }

    // MARK: Call Directory

    func refreshStatus() {
        CXCallDirectoryManager.sharedInstance.getEnabledStatusForExtension(
            withIdentifier: AppIdentifiers.callDirectoryExtension
        ) { status, error in
            Task { @MainActor in
                if let error = error {
                    self.callDirectoryStatus = .failed(error.localizedDescription)
                    return
                }
                switch status {
                case .enabled:
                    self.callDirectoryStatus = .enabled
                case .disabled:
                    self.callDirectoryStatus = .disabled
                case .unknown:
                    self.callDirectoryStatus = .unknown
                @unknown default:
                    self.callDirectoryStatus = .unknown
                }
            }
        }
    }

    /// Asks iOS to rebuild the call blocking list from the shared store.
    func reloadCallDirectory() {
        refreshFromStore()
        CXCallDirectoryManager.sharedInstance.reloadExtension(
            withIdentifier: AppIdentifiers.callDirectoryExtension
        ) { error in
            Task { @MainActor in
                if let error = error, self.callDirectoryStatus == .enabled {
                    self.errorMessage = "Could not update call blocking: \(error.localizedDescription)"
                }
                self.refreshStatus()
            }
        }
    }

    func openCallBlockingSettings() {
        CXCallDirectoryManager.sharedInstance.openSettings { error in
            if let error = error {
                Task { @MainActor in
                    self.errorMessage = "Could not open Settings: \(error.localizedDescription)"
                }
            }
        }
    }

    // MARK: Community

    /// Downloads the community blocklist. Returns whether the sync succeeded.
    @discardableResult
    func syncNow() async -> Bool {
        guard !isSyncing else { return false }
        isSyncing = true
        defer { isSyncing = false }
        do {
            let client = try CommunityClient(baseURL: preferences.backendBaseURL)
            let changed = try await CommunitySync.run(store: store, client: client)
            if changed {
                reloadCallDirectory()
            } else {
                refreshFromStore()
            }
            return true
        } catch {
            refreshFromStore()
            errorMessage = "Sync failed: \(error.localizedDescription)"
            return false
        }
    }

    func syncIfStale() async {
        guard settings.useCommunity else { return }
        if let last = community.lastSync, Date().timeIntervalSince(last) < BackgroundRefresh.interval {
            return
        }
        await syncNow()
    }

    func lookup(_ raw: String) async throws -> NumberLookup {
        let client = try CommunityClient(baseURL: preferences.backendBaseURL)
        return try await client.lookup(number: normalized(raw))
    }

    func report(_ raw: String, channel: Channel, verdict: ReportVerdict, category: String?) async throws -> ReportResponse {
        let client = try CommunityClient(baseURL: preferences.backendBaseURL)
        // The backend accepts categories of up to 40 characters.
        let trimmedCategory = category.map { String($0.trimmingCharacters(in: .whitespacesAndNewlines).prefix(40)) }
        let request = ReportRequest(
            number: normalized(raw),
            channel: channel,
            verdict: verdict,
            category: (trimmedCategory?.isEmpty ?? true) ? nil : trimmedCategory,
            installId: store.installId()
        )
        return try await client.report(request)
    }

    // MARK: Detection

    func evaluate(channel: Channel, sender: String, body: String) -> Verdict? {
        let event = Event(
            channel: channel,
            number: sender.isEmpty ? nil : sender,
            body: channel == .sms ? body : nil,
            isContact: false,
            verification: .unknown
        )
        return engine?.evaluate(event, context: context)
    }
}
