import Foundation

/// Block/allow list entries (SPEC §2).
public enum NumberPattern {
    /// Whether the raw entry is a wildcard pattern (`*` or `?`).
    public static func isWildcard(_ entry: String) -> Bool {
        entry.unicodeScalars.contains { $0 == "*" || $0 == "?" }
    }

    /// Prepares a list entry for matching. `nil` means the entry is ignored.
    public static func prepare(_ entry: String, countryCode: String = "1") -> String? {
        guard isWildcard(entry) else {
            return PhoneNumbers.normalize(entry, countryCode: countryCode)
        }
        let removed: Set<UInt32> = [0x28, 0x29, 0x2D, 0x2E] // ( ) - .
        let kept = entry.unicodeScalars.filter { !TextRules.isWhitespace($0) && !removed.contains($0.value) }
        let pattern = TextRules.string(kept.map(TextRules.asciiUppercased))
        return pattern.isEmpty ? nil : pattern
    }

    /// Glob match of a prepared pattern against a whole normalized number:
    /// `?` is exactly one character, `*` any run (including empty).
    public static func globMatch(_ pattern: String, _ text: String) -> Bool {
        let p = Array(pattern.unicodeScalars)
        let t = Array(text.unicodeScalars)
        var pi = 0
        var ti = 0
        var star = -1
        var mark = 0
        while ti < t.count {
            if pi < p.count && (p[pi] == "?" || p[pi] == t[ti]) {
                pi += 1
                ti += 1
            } else if pi < p.count && p[pi] == "*" {
                star = pi
                mark = ti
                pi += 1
            } else if star != -1 {
                pi = star + 1
                mark += 1
                ti = mark
            } else {
                return false
            }
        }
        while pi < p.count && p[pi] == "*" {
            pi += 1
        }
        return pi == p.count
    }

    /// Whether any raw entry matches the normalized `number`.
    public static func matchesAny(_ entries: [String], number: String, countryCode: String = "1") -> Bool {
        entries.contains { entry in
            guard let pattern = prepare(entry, countryCode: countryCode) else { return false }
            return globMatch(pattern, number)
        }
    }
}
