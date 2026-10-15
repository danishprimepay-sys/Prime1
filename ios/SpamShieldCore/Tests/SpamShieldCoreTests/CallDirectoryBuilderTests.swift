import XCTest
@testable import SpamShieldCore

final class CallDirectoryBuilderTests: XCTestCase {
    private let thresholds = Rules.Thresholds(block: 0.85, warn: 0.55)

    private func build(
        blocklist: [String] = [],
        allowlist: [String] = [],
        community: [String: Double] = [:]
    ) -> CallDirectoryEntries {
        CallDirectoryBuilder.build(
            blocklist: blocklist,
            allowlist: allowlist,
            community: community,
            thresholds: thresholds
        )
    }

    func testExactEntriesAreNormalizedSortedAndDeduplicated() {
        let entries = build(blocklist: [
            "(415) 555-0100",
            "+1 202 555 0123",
            "202.555.0123",
            "+44 20 7946 0958",
            "72345", // short code: not expressible in CallKit
            "Amazon", // alphanumeric
            "",
        ])
        XCTAssertEqual(entries.blocking, [12_025_550_123, 14_155_550_100, 442_079_460_958])
        XCTAssertEqual(entries.identification, [])
    }

    func testQuestionMarkPatternsExpand() {
        let entries = build(blocklist: ["+1 (212) 555-01??"])
        XCTAssertEqual(entries.blocking.count, 100)
        XCTAssertEqual(entries.blocking.first, 12_125_550_100)
        XCTAssertEqual(entries.blocking.last, 12_125_550_199)
        XCTAssertEqual(entries.blocking, entries.blocking.sorted())
    }

    func testStarAndUnsupportedPatternsAreSkipped() {
        let entries = build(blocklist: ["+1800555*", "*", "+1212?55?0123", "1212555????", "spam?"])
        XCTAssertEqual(entries.blocking, [])
    }

    func testExpansionCap() {
        // The first pattern uses the whole budget, so the later ones are skipped.
        let entries = build(blocklist: ["+1212555????", "+1313555????", "+1414555012?"])
        XCTAssertEqual(entries.blocking.count, 10_000)
        XCTAssertEqual(entries.blocking.first, 12_125_550_000)
        XCTAssertEqual(entries.blocking.last, 12_125_559_999)

        let tooBig = build(blocklist: ["+121255?????", "+1414555012?"])
        XCTAssertEqual(tooBig.blocking.count, 10)
        XCTAssertEqual(tooBig.blocking.first, 14_145_550_120)
    }

    func testAllowlistExcludesNumbers() {
        let entries = build(
            blocklist: ["+1212555010?", "+12025550123"],
            allowlist: ["+12125550105", "202-555-0123", "+1313*"],
            community: ["+13135550100": 0.99, "+13135550101": 0.6, "+14155550100": 0.6]
        )
        XCTAssertEqual(entries.blocking, [
            12_125_550_100, 12_125_550_101, 12_125_550_102, 12_125_550_103, 12_125_550_104,
            12_125_550_106, 12_125_550_107, 12_125_550_108, 12_125_550_109,
        ])
        XCTAssertEqual(entries.identification.map { $0.number }, [14_155_550_100])
    }

    func testCommunitySplitsIntoBlockAndIdentify() {
        let entries = build(
            blocklist: ["+12025550123"],
            community: [
                "+12025550123": 0.6, // already blocked: must not be identified too
                "+12025550100": 0.85, // block threshold is inclusive
                "+12025550101": 0.84,
                "+12025550102": 0.55, // warn threshold is inclusive
                "+12025550103": 0.54, // below warn: ignored
                "72345": 0.99, // not E.164
            ]
        )
        XCTAssertEqual(entries.blocking, [12_025_550_100, 12_025_550_123])
        XCTAssertEqual(
            entries.identification,
            [
                IdentificationEntry(number: 12_025_550_101, label: "Suspected spam"),
                IdentificationEntry(number: 12_025_550_102, label: "Suspected spam"),
            ]
        )
    }

    func testBuildFromStoreHonorsCommunitySetting() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("CallDirectoryBuilderTests-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = SharedStore(directory: directory)
        try store.setBlocklist(["+12025550123"])
        try store.setCommunity(CommunitySnapshot(scores: ["+12025550100": 0.9]))

        XCTAssertEqual(
            CallDirectoryBuilder.build(store: store, thresholds: thresholds).blocking,
            [12_025_550_100, 12_025_550_123]
        )
        try store.setSettings(Settings(useCommunity: false))
        XCTAssertEqual(CallDirectoryBuilder.build(store: store, thresholds: thresholds).blocking, [12_025_550_123])
    }
}
