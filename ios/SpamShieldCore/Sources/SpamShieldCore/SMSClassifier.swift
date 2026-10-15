import Foundation

/// Logistic-regression SMS classifier (SPEC §5).
public final class SMSClassifier {
    public let model: ClassifierModel
    private let replacements: [CompiledReplacement]

    private struct CompiledReplacement {
        let regex: PortableRegex
        let token: String
    }

    public init(model: ClassifierModel) throws {
        self.model = model
        var compiled: [CompiledReplacement] = []
        for replacement in model.tokenizer.replacements {
            compiled.append(CompiledReplacement(regex: try PortableRegex(replacement.pattern), token: replacement.token))
        }
        replacements = compiled
    }

    /// Sorted unique tokens of `text`.
    public func tokenize(_ text: String?) -> [String] {
        var s = (text ?? "").lowercased()
        for replacement in replacements {
            s = replacement.regex.replaceAll(in: s, with: " " + replacement.token + " ")
        }
        var tokens = Set<String>()
        var piece: [Unicode.Scalar] = []
        func flush() {
            if piece.count >= model.tokenizer.minTokenLength {
                tokens.insert(TextRules.string(piece))
            }
            piece.removeAll(keepingCapacity: true)
        }
        for scalar in s.unicodeScalars {
            if TextRules.isTokenCharacter(scalar) {
                piece.append(scalar)
            } else {
                flush()
            }
        }
        flush()
        return tokens.sorted()
    }

    /// Spam probability; an empty or nil body gives `sigmoid(bias)`.
    public func probability(_ text: String?) -> Double {
        var logit = model.bias
        // Sorted order keeps the floating-point sum identical to the reference.
        for token in tokenize(text) {
            logit += model.weights[token] ?? 0.0
        }
        return 1.0 / (1.0 + exp(-logit))
    }
}
