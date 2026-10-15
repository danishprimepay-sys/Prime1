import XCTest
@testable import SpamShieldCore

private struct NormalizeVector: Decodable {
    let raw: String?
    let countryCode: String
    let expected: String?
}

private struct PatternVector: Decodable {
    let pattern: String
    let number: String
    let matches: Bool
}

private struct TokenizeVector: Decodable {
    let text: String
    let tokens: [String]
}

private struct ClassifierVector: Decodable {
    let text: String
    let probability: Double
}

private struct EngineVector: Decodable {
    struct Expect: Decodable {
        let action: Action
        let reasons: [String]
        let score: Double
    }

    let name: String
    let event: Event
    let context: DetectionContext
    let expect: Expect
}

final class VectorTests: XCTestCase {
    private let tolerance = 1e-9

    func testNormalizeVectors() throws {
        let vectors = try RepoFixtures.vectors(NormalizeVector.self, "normalize")
        XCTAssertFalse(vectors.isEmpty)
        for v in vectors {
            XCTAssertEqual(
                PhoneNumbers.normalize(v.raw, countryCode: v.countryCode), v.expected,
                "normalize(\(String(describing: v.raw)), \(v.countryCode))"
            )
        }
    }

    func testPatternVectors() throws {
        let vectors = try RepoFixtures.vectors(PatternVector.self, "patterns")
        XCTAssertFalse(vectors.isEmpty)
        for v in vectors {
            let prepared = NumberPattern.prepare(v.pattern)
            let matches = prepared.map { NumberPattern.globMatch($0, v.number) } ?? false
            XCTAssertEqual(matches, v.matches, "pattern \(v.pattern) vs \(v.number)")
            XCTAssertEqual(NumberPattern.matchesAny([v.pattern], number: v.number), v.matches)
        }
    }

    func testTokenizeVectors() throws {
        let classifier = try RepoFixtures.sharedEngine().classifier
        let vectors = try RepoFixtures.vectors(TokenizeVector.self, "tokenize")
        XCTAssertFalse(vectors.isEmpty)
        for v in vectors {
            XCTAssertEqual(classifier?.tokenize(v.text), v.tokens, "tokenize(\(v.text))")
        }
    }

    func testClassifierVectors() throws {
        let classifier = try XCTUnwrap(RepoFixtures.sharedEngine().classifier)
        let vectors = try RepoFixtures.vectors(ClassifierVector.self, "classifier")
        XCTAssertFalse(vectors.isEmpty)
        for v in vectors {
            XCTAssertEqual(classifier.probability(v.text), v.probability, accuracy: tolerance, "classify(\(v.text))")
        }
    }

    func testEngineVectors() throws {
        let engine = try RepoFixtures.sharedEngine()
        let vectors = try RepoFixtures.vectors(EngineVector.self, "engine")
        XCTAssertFalse(vectors.isEmpty)
        for v in vectors {
            let verdict = engine.evaluate(v.event, context: v.context)
            XCTAssertEqual(verdict.action, v.expect.action, v.name)
            XCTAssertEqual(verdict.reasons, v.expect.reasons, v.name)
            XCTAssertEqual(verdict.score, v.expect.score, accuracy: tolerance, v.name)
        }
    }

    func testBundledResourcesMatchShared() throws {
        let bundledRules = try SpamEngine.bundledRules()
        XCTAssertEqual(bundledRules, try RepoFixtures.decode(Rules.self, "shared/rules.json"))
        let bundled = try SpamEngine.bundled()
        XCTAssertEqual(
            bundled.classifier?.model,
            try RepoFixtures.decode(ClassifierModel.self, "shared/model/sms_classifier.json")
        )
    }

    func testBundledEngineAgreesWithVectors() throws {
        let engine = try SpamEngine.bundled()
        for v in try RepoFixtures.vectors(EngineVector.self, "engine") {
            let verdict = engine.evaluate(v.event, context: v.context)
            XCTAssertEqual(verdict.action, v.expect.action, v.name)
            XCTAssertEqual(verdict.reasons, v.expect.reasons, v.name)
            XCTAssertEqual(verdict.score, v.expect.score, accuracy: tolerance, v.name)
        }
    }
}
