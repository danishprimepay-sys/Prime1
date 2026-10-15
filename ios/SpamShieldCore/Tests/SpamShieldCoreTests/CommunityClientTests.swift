import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import XCTest
@testable import SpamShieldCore

/// Serves canned responses to a URLSession so the client can be tested offline.
final class StubURLProtocol: URLProtocol {
    struct Stub {
        var status: Int
        var headers: [String: String]
        var body: Data
    }

    static var handler: ((URLRequest) -> Stub)?
    static var requests: [URLRequest] = []

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        StubURLProtocol.requests.append(request)
        guard let handler = StubURLProtocol.handler, let url = request.url else {
            client?.urlProtocol(self, didFailWithError: CommunityError.invalidResponse)
            return
        }
        let stub = handler(request)
        guard let response = HTTPURLResponse(
            url: url, statusCode: stub.status, httpVersion: "HTTP/1.1", headerFields: stub.headers
        ) else {
            client?.urlProtocol(self, didFailWithError: CommunityError.invalidResponse)
            return
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: stub.body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

final class CommunityClientTests: XCTestCase {
    private func makeClient(_ base: String = "https://api.spamshield.example/") throws -> CommunityClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        return try CommunityClient(baseURL: base, session: URLSession(configuration: configuration))
    }

    override func tearDown() {
        StubURLProtocol.handler = nil
        StubURLProtocol.requests = []
        super.tearDown()
    }

    // MARK: Decoding

    func testDecodesBlocklist() throws {
        let json = #"""
        {"generatedAt": "2026-10-15T12:00:00Z", "minScore": 0.5,
         "entries": [{"number": "+12025550123", "score": 0.92}, {"number": "+18005550000", "score": 0.61}]}
        """#
        let response = try JSONDecoder().decode(BlocklistResponse.self, from: Data(json.utf8))
        XCTAssertEqual(response.generatedAt, "2026-10-15T12:00:00Z")
        XCTAssertEqual(response.minScore, 0.5)
        XCTAssertEqual(response.entries, [
            BlocklistResponse.Entry(number: "+12025550123", score: 0.92),
            BlocklistResponse.Entry(number: "+18005550000", score: 0.61),
        ])
    }

    func testDecodesNumberLookup() throws {
        let json = #"""
        {"number": "+12025550123", "score": 0.8, "spamReports": 12, "notSpamReports": 1,
         "lastReportedAt": "2026-10-14T08:30:00Z"}
        """#
        let lookup = try JSONDecoder().decode(NumberLookup.self, from: Data(json.utf8))
        XCTAssertEqual(lookup, NumberLookup(
            number: "+12025550123", score: 0.8, spamReports: 12, notSpamReports: 1,
            lastReportedAt: "2026-10-14T08:30:00Z"
        ))

        let unknown = #"{"number": "+12025550124", "score": 0, "spamReports": 0, "notSpamReports": 0, "lastReportedAt": null}"#
        XCTAssertNil(try JSONDecoder().decode(NumberLookup.self, from: Data(unknown.utf8)).lastReportedAt)
    }

    func testDecodesReportResponse() throws {
        let json = #"{"accepted": true, "number": "+12025550123"}"#
        XCTAssertEqual(
            try JSONDecoder().decode(ReportResponse.self, from: Data(json.utf8)),
            ReportResponse(accepted: true, number: "+12025550123")
        )
    }

    func testEncodesReportRequest() throws {
        let request = ReportRequest(number: "+12025550123", channel: .sms, verdict: .notSpam, installId: "abc")
        let object = try JSONSerialization.jsonObject(with: JSONEncoder().encode(request)) as? [String: Any]
        let dict = try XCTUnwrap(object)
        XCTAssertEqual(dict["number"] as? String, "+12025550123")
        XCTAssertEqual(dict["channel"] as? String, "sms")
        XCTAssertEqual(dict["verdict"] as? String, "not_spam")
        XCTAssertEqual(dict["installId"] as? String, "abc")
        XCTAssertNil(dict["category"])

        let withCategory = ReportRequest(
            number: "+12025550123", channel: .call, verdict: .spam, category: "robocall", installId: "abc"
        )
        let object2 = try JSONSerialization.jsonObject(with: JSONEncoder().encode(withCategory)) as? [String: Any]
        XCTAssertEqual(object2?["category"] as? String, "robocall")
        XCTAssertEqual(object2?["channel"] as? String, "call")
        XCTAssertEqual(object2?["verdict"] as? String, "spam")
    }

    // MARK: Requests

    func testBuildsRequests() throws {
        let client = try makeClient("https://api.spamshield.example//")
        XCTAssertEqual(client.baseURL, "https://api.spamshield.example")

        let blocklist = try client.blocklistRequest(minScore: 0.5, etag: "\"v1\"")
        XCTAssertEqual(blocklist.url?.absoluteString, "https://api.spamshield.example/v1/blocklist?minScore=0.5")
        XCTAssertEqual(blocklist.value(forHTTPHeaderField: "If-None-Match"), "\"v1\"")
        XCTAssertNil(try client.blocklistRequest(minScore: 0.5, etag: nil).value(forHTTPHeaderField: "If-None-Match"))

        let lookup = try client.lookupRequest(number: "+1 202 555 0123")
        XCTAssertEqual(lookup.url?.absoluteString, "https://api.spamshield.example/v1/numbers/%2B1%20202%20555%200123")

        let report = try client.reportRequest(ReportRequest(number: "+1", channel: .call, verdict: .spam, installId: "i"))
        XCTAssertEqual(report.httpMethod, "POST")
        XCTAssertEqual(report.url?.absoluteString, "https://api.spamshield.example/v1/reports")
        XCTAssertEqual(report.value(forHTTPHeaderField: "Content-Type"), "application/json")
        XCTAssertNotNil(report.httpBody)
    }

    func testRejectsInvalidBaseURL() {
        XCTAssertThrowsError(try CommunityClient(baseURL: "not a url"))
        XCTAssertThrowsError(try CommunityClient(baseURL: "ftp://example.com"))
    }

    // MARK: Sync over a stubbed session

    func testSyncStoresBlocklistThenHonorsNotModified() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("CommunityClientTests-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = SharedStore(directory: directory)
        let client = try makeClient()
        let body = #"""
        {"generatedAt": "2026-10-15T12:00:00Z", "minScore": 0.5,
         "entries": [{"number": "+12025550123", "score": 0.92}, {"number": "1 (800) 555-0000", "score": 0.6}]}
        """#
        StubURLProtocol.handler = { request in
            if request.value(forHTTPHeaderField: "If-None-Match") == "\"v1\"" {
                return StubURLProtocol.Stub(status: 304, headers: ["ETag": "\"v1\""], body: Data())
            }
            return StubURLProtocol.Stub(
                status: 200,
                headers: ["Content-Type": "application/json", "ETag": "\"v1\""],
                body: Data(body.utf8)
            )
        }

        let first = Date(timeIntervalSince1970: 1_700_000_000)
        let changed = try await CommunitySync.run(store: store, client: client, now: first)
        XCTAssertTrue(changed)
        XCTAssertEqual(store.community(), CommunitySnapshot(
            scores: ["+12025550123": 0.92, "+18005550000": 0.6],
            etag: "\"v1\"",
            generatedAt: "2026-10-15T12:00:00Z",
            lastSync: first
        ))

        let second = Date(timeIntervalSince1970: 1_700_003_600)
        let changedAgain = try await CommunitySync.run(store: store, client: client, now: second)
        XCTAssertFalse(changedAgain)
        XCTAssertEqual(store.community().scores.count, 2)
        XCTAssertEqual(store.community().lastSync, second)
        XCTAssertEqual(StubURLProtocol.requests.count, 2)
    }

    func testHTTPErrorsThrow() async throws {
        let client = try makeClient()
        StubURLProtocol.handler = { _ in StubURLProtocol.Stub(status: 500, headers: [:], body: Data()) }
        do {
            _ = try await client.lookup(number: "+12025550123")
            XCTFail("expected an error")
        } catch let error as CommunityError {
            XCTAssertEqual(error, .httpStatus(500, detail: nil))
        }

        StubURLProtocol.handler = { _ in
            StubURLProtocol.Stub(status: 400, headers: [:], body: Data(#"{"detail": "bad number"}"#.utf8))
        }
        do {
            _ = try await client.report(ReportRequest(number: "x", channel: .sms, verdict: .spam, installId: "i"))
            XCTFail("expected an error")
        } catch let error as CommunityError {
            XCTAssertEqual(error, .httpStatus(400, detail: "bad number"))
            XCTAssertEqual(error.localizedDescription, "Server error 400: bad number")
        }

        StubURLProtocol.handler = { _ in
            StubURLProtocol.Stub(status: 429, headers: ["Retry-After": "30"], body: Data(#"{"detail": "slow down"}"#.utf8))
        }
        do {
            _ = try await client.fetchBlocklist()
            XCTFail("expected an error")
        } catch let error as CommunityError {
            XCTAssertEqual(error, .rateLimited(retryAfter: 30))
        }
    }
}
