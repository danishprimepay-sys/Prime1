import XCTest
@testable import SpamShieldCore

/// Edge cases beyond the shared vectors, checked against the Python reference.
final class EngineDetailTests: XCTestCase {
    func testValidCallerNumber() {
        XCTAssertTrue(PhoneNumbers.isValidCallerNumber("+12025550123"))
        XCTAssertFalse(PhoneNumbers.isValidCallerNumber("+11025550123"))
        XCTAssertFalse(PhoneNumbers.isValidCallerNumber("+12021550123"))
        XCTAssertFalse(PhoneNumbers.isValidCallerNumber("+1202555012"))
        XCTAssertTrue(PhoneNumbers.isValidCallerNumber("+442079460958"))
        XCTAssertFalse(PhoneNumbers.isValidCallerNumber("+4420794"))
        XCTAssertFalse(PhoneNumbers.isValidCallerNumber("+"))
        XCTAssertFalse(PhoneNumbers.isValidCallerNumber("72345"))
        XCTAssertFalse(PhoneNumbers.isValidCallerNumber("SPAMLIKELY"))
    }

    func testNeighborSpoof() {
        XCTAssertTrue(PhoneNumbers.isNeighborSpoof("+12025551111", "+12025559876"))
        XCTAssertFalse(PhoneNumbers.isNeighborSpoof("+12025559876", "+12025559876"))
        XCTAssertFalse(PhoneNumbers.isNeighborSpoof("+12025561111", "+12025559876"))
        XCTAssertFalse(PhoneNumbers.isNeighborSpoof(nil, "+12025559876"))
        XCTAssertFalse(PhoneNumbers.isNeighborSpoof("+442025551111", "+442025559876"))
    }

    func testUnicodeWhitespaceAndNewlinesInRegexes() throws {
        XCTAssertEqual(PhoneNumbers.normalize("\u{3000}Bank\u{00A0}Alert\u{1F}"), "BANKALERT")
        XCTAssertNil(PhoneNumbers.normalize("\u{2028}\u{1C}"))
        // `.` must not cross a newline, but does cross other line separators.
        let engine = try RepoFixtures.sharedEngine()
        let settings = Settings(useClassifier: false)
        let crossNewline = engine.evaluate(
            Event(channel: .sms, number: "+12025550123", body: "toll\nunpaid"),
            context: DetectionContext(settings: settings)
        )
        XCTAssertEqual(crossNewline.reasons, [])
        let crossLineSeparator = engine.evaluate(
            Event(channel: .sms, number: "+12025550123", body: "toll\u{2028}unpaid"),
            context: DetectionContext(settings: settings)
        )
        XCTAssertEqual(crossLineSeparator.reasons, ["keyword:toll_unpaid"])
        // `$` also matches before a single trailing newline.
        let opener = engine.evaluate(
            Event(channel: .sms, number: "+12025550123", body: "Hi is this Sam?\n"),
            context: DetectionContext(settings: settings)
        )
        XCTAssertEqual(opener.reasons, ["keyword:wrong_number_opener"])
    }

    func testURLHosts() {
        XCTAssertEqual(URLHosts.hosts(in: "go to https://a.b-c.top/x and 10.0.0.1:80 now"), ["a.b-c.top", "10.0.0.1"])
        XCTAssertEqual(URLHosts.topLevelDomain("usps-help.top"), "top")
        XCTAssertTrue(URLHosts.isShortener("www.bit.ly", shorteners: ["bit.ly"]))
        XCTAssertFalse(URLHosts.isShortener("notbit.ly", shorteners: ["bit.ly"]))
    }
}
