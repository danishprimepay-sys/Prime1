import XCTest
@testable import SpamShieldCore

final class SharedStoreTests: XCTestCase {
    private var directory: URL!
    private var store: SharedStore!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("SharedStoreTests-\(UUID().uuidString)", isDirectory: true)
        store = SharedStore(directory: directory)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    func testDefaultsWhenEmpty() {
        XCTAssertEqual(store.blocklist(), [])
        XCTAssertEqual(store.allowlist(), [])
        XCTAssertEqual(store.settings(), Settings())
        XCTAssertEqual(store.preferences(), AppPreferences())
        XCTAssertEqual(store.preferences().backendBaseURL, "https://api.spamshield.example")
        XCTAssertEqual(store.community(), CommunitySnapshot())
        XCTAssertEqual(store.flaggedMessages(), [])
        XCTAssertEqual(store.context(), DetectionContext())
    }

    func testRoundTrip() throws {
        let settings = Settings(
            blockHiddenNumbers: true,
            detectNeighborSpoofing: false,
            userNumber: "(202) 555-9876",
            useClassifier: false,
            useCommunity: true,
            countryCode: "44"
        )
        let syncDate = Date(timeIntervalSince1970: 1_700_000_000)
        let snapshot = CommunitySnapshot(
            scores: ["+12025550123": 0.92, "+18005550000": 0.6],
            etag: "\"abc\"",
            generatedAt: "2026-10-15T00:00:00Z",
            lastSync: syncDate
        )
        try store.setBlocklist(["+1800555*", "(202) 555-0123"])
        try store.setAllowlist(["+12025550199"])
        try store.setSettings(settings)
        try store.setCommunity(snapshot)
        try store.setPreferences(AppPreferences(backendBaseURL: "http://localhost:8000", logMessageBodies: true))

        // A fresh instance reads what the first one wrote.
        let reopened = SharedStore(directory: directory)
        XCTAssertEqual(reopened.blocklist(), ["+1800555*", "(202) 555-0123"])
        XCTAssertEqual(reopened.allowlist(), ["+12025550199"])
        XCTAssertEqual(reopened.settings(), settings)
        XCTAssertEqual(reopened.community(), snapshot)
        XCTAssertEqual(reopened.preferences().backendBaseURL, "http://localhost:8000")
        XCTAssertTrue(reopened.preferences().logMessageBodies)
        XCTAssertEqual(
            reopened.context(),
            DetectionContext(
                blocklist: ["+1800555*", "(202) 555-0123"],
                allowlist: ["+12025550199"],
                community: snapshot.scores,
                settings: settings
            )
        )
    }

    func testInstallIdIsStable() {
        let id = store.installId()
        XCTAssertNotNil(UUID(uuidString: id))
        XCTAssertEqual(store.installId(), id)
        XCTAssertEqual(SharedStore(directory: directory).installId(), id)
    }

    func testFlaggedLogIsCapped() throws {
        for i in 0..<5 {
            try store.appendFlaggedMessage(
                FlaggedMessage(
                    sender: "+1202555000\(i)",
                    action: .block,
                    score: 0.9,
                    reasons: ["classifier"],
                    date: Date(timeIntervalSince1970: TimeInterval(1_700_000_000 + i))
                ),
                limit: 3
            )
        }
        let log = store.flaggedMessages()
        XCTAssertEqual(log.map { $0.sender }, ["+12025550002", "+12025550003", "+12025550004"])
        XCTAssertEqual(log.last?.date, Date(timeIntervalSince1970: 1_700_000_004))
        try store.clearFlaggedMessages()
        XCTAssertEqual(store.flaggedMessages(), [])
    }

    func testCorruptFileFallsBackToDefault() throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
        try Data("not json".utf8).write(to: directory.appendingPathComponent("settings.json"))
        XCTAssertEqual(store.settings(), Settings())
    }

    func testPartialSettingsUseDefaults() throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
        try Data(#"{"useClassifier": false}"#.utf8).write(to: directory.appendingPathComponent("settings.json"))
        XCTAssertEqual(store.settings(), Settings(useClassifier: false))
    }
}
