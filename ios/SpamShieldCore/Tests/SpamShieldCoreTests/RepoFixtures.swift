import Foundation
import XCTest
@testable import SpamShieldCore

/// Locates files in the repository (the vectors are read in place, not copied).
enum RepoFixtures {
    static let root: URL = {
        var url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while url.pathComponents.count > 1 {
            let vectors = url.appendingPathComponent("shared", isDirectory: true)
                .appendingPathComponent("test-vectors", isDirectory: true)
            if FileManager.default.fileExists(atPath: vectors.path) {
                return url
            }
            url = url.deletingLastPathComponent()
        }
        fatalError("Could not find the repository root (a directory containing shared/test-vectors) above \(#filePath)")
    }()

    static func url(_ relativePath: String) -> URL {
        relativePath.split(separator: "/").reduce(root) { $0.appendingPathComponent(String($1)) }
    }

    static func decode<T: Decodable>(_ type: T.Type, _ relativePath: String) throws -> T {
        try JSONDecoder().decode(type, from: Data(contentsOf: url(relativePath)))
    }

    static func vectors<T: Decodable>(_ type: T.Type, _ name: String) throws -> [T] {
        try decode([T].self, "shared/test-vectors/\(name).json")
    }

    /// The engine built from the canonical files in shared/.
    static func sharedEngine() throws -> SpamEngine {
        let rules = try decode(Rules.self, "shared/rules.json")
        let model = try decode(ClassifierModel.self, "shared/model/sms_classifier.json")
        return try SpamEngine(rules: rules, classifier: SMSClassifier(model: model))
    }
}
