import Foundation

/// `shared/rules.json`.
public struct Rules: Codable, Equatable {
    public struct Thresholds: Codable, Equatable {
        public var block: Double
        public var warn: Double

        public init(block: Double, warn: Double) {
            self.block = block
            self.warn = warn
        }
    }

    public struct Community: Codable, Equatable {
        public var weight: Double
    }

    public struct Call: Codable, Equatable {
        public var hiddenWeight: Double
        public var verificationFailedWeight: Double
        public var invalidNumberWeight: Double
        public var neighborSpoofWeight: Double
    }

    public struct SMS: Codable, Equatable {
        public var senderEmailWeight: Double
        public var classifier: Classifier
        public var keywordRules: [KeywordRule]
        public var urls: URLs
    }

    public struct Classifier: Codable, Equatable {
        public var minProbability: Double
        public var weight: Double
    }

    public struct KeywordRule: Codable, Equatable {
        public var id: String
        public var pattern: String
        public var weight: Double
    }

    public struct URLs: Codable, Equatable {
        public var shortenerWeight: Double
        public var ipHostWeight: Double
        public var suspiciousTldWeight: Double
        public var shorteners: [String]
        public var suspiciousTlds: [String]
    }

    public var version: Int
    public var thresholds: Thresholds
    public var community: Community
    public var call: Call
    public var sms: SMS
}

/// `shared/model/sms_classifier.json` (logistic regression over unique tokens).
public struct ClassifierModel: Codable, Equatable {
    public struct Tokenizer: Codable, Equatable {
        public var replacements: [Replacement]
        public var minTokenLength: Int
    }

    public struct Replacement: Codable, Equatable {
        public var pattern: String
        public var token: String
    }

    public var version: Int
    public var bias: Double
    public var tokenizer: Tokenizer
    public var weights: [String: Double]
}
