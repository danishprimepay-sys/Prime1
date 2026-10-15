import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum CommunityError: Error, Equatable {
    case invalidBaseURL(String)
    case invalidResponse
    /// A non-2xx status; `detail` is the server's `{"detail": ...}` message when present.
    case httpStatus(Int, detail: String?)
    /// 429, with the `Retry-After` delay in seconds when the server sent one.
    case rateLimited(retryAfter: TimeInterval?)
}

extension CommunityError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .invalidBaseURL(let url):
            return "Invalid server URL: \(url)"
        case .invalidResponse:
            return "The server sent an invalid response."
        case let .httpStatus(code, detail):
            return detail.map { "Server error \(code): \($0)" } ?? "Server error \(code)."
        case .rateLimited(let retryAfter):
            guard let retryAfter = retryAfter else { return "Too many requests. Try again later." }
            return "Too many requests. Try again in \(Int(retryAfter.rounded(.up))) seconds."
        }
    }
}

/// FastAPI-style error body.
private struct ErrorBody: Decodable {
    let detail: String
}

public enum BlocklistFetchResult: Equatable {
    /// The server answered 304 for the ETag we sent.
    case notModified
    case updated(BlocklistResponse, etag: String?)
}

/// Client for the SpamShield community backend.
public final class CommunityClient {
    public static let defaultBaseURL = "https://api.spamshield.example"

    /// Base URL without a trailing slash.
    public let baseURL: String
    private let session: URLSession

    public init(baseURL: String = CommunityClient.defaultBaseURL, session: URLSession = .shared) throws {
        var trimmed = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        while trimmed.hasSuffix("/") {
            trimmed.removeLast()
        }
        guard let url = URL(string: trimmed), let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https", url.host != nil else {
            throw CommunityError.invalidBaseURL(baseURL)
        }
        self.baseURL = trimmed
        self.session = session
    }

    // MARK: Requests (exposed for tests)

    public func blocklistRequest(minScore: Double, etag: String?) throws -> URLRequest {
        var request = URLRequest(url: try url("/v1/blocklist?minScore=\(minScore)"))
        // Let the server, not URLCache, answer conditional requests.
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let etag = etag, !etag.isEmpty {
            request.setValue(etag, forHTTPHeaderField: "If-None-Match")
        }
        return request
    }

    public func reportRequest(_ report: ReportRequest) throws -> URLRequest {
        var request = URLRequest(url: try url("/v1/reports"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = try JSONEncoder().encode(report)
        return request
    }

    public func lookupRequest(number: String) throws -> URLRequest {
        var request = URLRequest(url: try url("/v1/numbers/" + CommunityClient.percentEncodePathSegment(number)))
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        return request
    }

    /// Percent-encodes everything except RFC 3986 unreserved characters, so
    /// `+` becomes `%2B`.
    public static func percentEncodePathSegment(_ value: String) -> String {
        let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return value.addingPercentEncoding(withAllowedCharacters: unreserved) ?? value
    }

    // MARK: Calls

    public func fetchBlocklist(minScore: Double = 0.5, etag: String? = nil) async throws -> BlocklistFetchResult {
        let (data, response) = try await send(try blocklistRequest(minScore: minScore, etag: etag))
        if response.statusCode == 304 {
            return .notModified
        }
        try CommunityClient.check(response, data: data)
        let body = try JSONDecoder().decode(BlocklistResponse.self, from: data)
        return .updated(body, etag: CommunityClient.header("ETag", in: response))
    }

    public func report(_ report: ReportRequest) async throws -> ReportResponse {
        let (data, response) = try await send(try reportRequest(report))
        try CommunityClient.check(response, data: data)
        return try JSONDecoder().decode(ReportResponse.self, from: data)
    }

    public func lookup(number: String) async throws -> NumberLookup {
        let (data, response) = try await send(try lookupRequest(number: number))
        try CommunityClient.check(response, data: data)
        return try JSONDecoder().decode(NumberLookup.self, from: data)
    }

    // MARK: Plumbing

    private func url(_ pathAndQuery: String) throws -> URL {
        guard let url = URL(string: baseURL + pathAndQuery) else {
            throw CommunityError.invalidBaseURL(baseURL)
        }
        return url
    }

    // Completion-handler based so it also builds with swift-corelibs-foundation.
    private func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<(Data, HTTPURLResponse), Error>) in
            let task = self.session.dataTask(with: request) { data, response, error in
                if let error = error {
                    continuation.resume(throwing: error)
                } else if let http = response as? HTTPURLResponse {
                    continuation.resume(returning: (data ?? Data(), http))
                } else {
                    continuation.resume(throwing: CommunityError.invalidResponse)
                }
            }
            task.resume()
        }
    }

    private static func check(_ response: HTTPURLResponse, data: Data) throws {
        let status = response.statusCode
        if (200..<300).contains(status) {
            return
        }
        if status == 429 {
            let retryAfter = header("Retry-After", in: response).flatMap { TimeInterval($0.trimmingCharacters(in: .whitespaces)) }
            throw CommunityError.rateLimited(retryAfter: retryAfter)
        }
        // `detail` may also be a structured validation error; only strings are surfaced.
        let detail = (try? JSONDecoder().decode(ErrorBody.self, from: data))?.detail
        throw CommunityError.httpStatus(status, detail: detail)
    }

    private static func header(_ name: String, in response: HTTPURLResponse) -> String? {
        for (key, value) in response.allHeaderFields {
            if let key = key as? String, key.caseInsensitiveCompare(name) == .orderedSame {
                return value as? String
            }
        }
        return nil
    }
}

/// Downloads the community blocklist into a `SharedStore`.
public enum CommunitySync {
    /// Returns `true` when the stored scores changed.
    @discardableResult
    public static func run(
        store: SharedStore,
        client: CommunityClient,
        minScore: Double = 0.5,
        now: Date = Date()
    ) async throws -> Bool {
        var snapshot = store.community()
        let result = try await client.fetchBlocklist(minScore: minScore, etag: snapshot.etag)
        switch result {
        case .notModified:
            snapshot.lastSync = now
            try store.setCommunity(snapshot)
            return false
        case let .updated(response, etag):
            let newScores = CommunitySync.scores(from: response, countryCode: store.settings().countryCode)
            let changed = newScores != snapshot.scores
            try store.setCommunity(CommunitySnapshot(
                scores: newScores,
                etag: etag,
                generatedAt: response.generatedAt,
                lastSync: now
            ))
            return changed
        }
    }

    /// Scores keyed by normalized number; duplicates keep the highest score.
    public static func scores(from response: BlocklistResponse, countryCode: String = "1") -> [String: Double] {
        var scores: [String: Double] = [:]
        for entry in response.entries {
            guard let key = PhoneNumbers.normalize(entry.number, countryCode: countryCode) else { continue }
            scores[key] = max(scores[key] ?? 0, entry.score)
        }
        return scores
    }
}
