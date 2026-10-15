import Foundation

/// A CallKit identification entry.
public struct IdentificationEntry: Equatable {
    /// E.164 digits without `+`.
    public var number: Int64
    public var label: String

    public init(number: Int64, label: String) {
        self.number = number
        self.label = label
    }
}

/// What the Call Directory extension hands to CallKit: both lists strictly
/// ascending, without duplicates, and disjoint.
public struct CallDirectoryEntries: Equatable {
    public var blocking: [Int64]
    public var identification: [IdentificationEntry]

    public init(blocking: [Int64], identification: [IdentificationEntry]) {
        self.blocking = blocking
        self.identification = identification
    }
}

/// Turns the stored lists into CallKit entries. CallKit only accepts exact
/// numbers, so `*` patterns (which still apply to texts) are skipped and
/// trailing-`?` patterns are expanded.
public enum CallDirectoryBuilder {
    public static let identificationLabel = "Suspected spam"
    /// Upper bound on numbers produced by expanding `?` patterns, in total.
    public static let maxExpansion = 10_000

    public static func build(
        blocklist: [String],
        allowlist: [String],
        community: [String: Double],
        thresholds: Rules.Thresholds,
        countryCode: String = "1",
        identificationLabel: String = CallDirectoryBuilder.identificationLabel
    ) -> CallDirectoryEntries {
        let allowPatterns = allowlist.compactMap { NumberPattern.prepare($0, countryCode: countryCode) }
        func isAllowed(_ number: Int64) -> Bool {
            let normalized = "+" + String(number)
            return allowPatterns.contains { NumberPattern.globMatch($0, normalized) }
        }

        var blocking = Set<Int64>()
        var expansionBudget = maxExpansion
        for entry in blocklist {
            if NumberPattern.isWildcard(entry) {
                guard let pattern = NumberPattern.prepare(entry, countryCode: countryCode),
                      let range = expansionRange(pattern),
                      range.count <= expansionBudget else { continue }
                expansionBudget -= range.count
                for number in range where !isAllowed(number) {
                    blocking.insert(number)
                }
            } else if let normalized = PhoneNumbers.normalize(entry, countryCode: countryCode),
                      let number = PhoneNumbers.e164Integer(normalized),
                      !isAllowed(number) {
                blocking.insert(number)
            }
        }

        var identification = Set<Int64>()
        for (key, score) in community {
            guard score >= thresholds.warn, let number = PhoneNumbers.e164Integer(key), !isAllowed(number) else {
                continue
            }
            if score >= thresholds.block {
                blocking.insert(number)
            } else {
                identification.insert(number)
            }
        }
        identification.subtract(blocking)

        return CallDirectoryEntries(
            blocking: blocking.sorted(),
            identification: identification.sorted().map { IdentificationEntry(number: $0, label: identificationLabel) }
        )
    }

    /// Convenience over everything in a `SharedStore`.
    public static func build(store: SharedStore, thresholds: Rules.Thresholds) -> CallDirectoryEntries {
        let settings = store.settings()
        return build(
            blocklist: store.blocklist(),
            allowlist: store.allowlist(),
            community: settings.useCommunity ? store.community().scores : [:],
            thresholds: thresholds,
            countryCode: settings.countryCode
        )
    }

    /// For a prepared pattern of the form `+<digits><one or more ?>`, the
    /// contiguous integer range it denotes. `nil` for anything else
    /// (including `*` patterns and expansions over `maxExpansion`).
    static func expansionRange(_ pattern: String) -> ClosedRange<Int64>? {
        let s = Array(pattern.unicodeScalars)
        guard s.first?.value == 0x2B else { return nil }
        let body = s.dropFirst()
        guard let firstWildcard = body.firstIndex(where: { $0 == "?" }) else { return nil }
        let prefix = body[body.startIndex..<firstWildcard]
        let wildcards = body[firstWildcard...]
        guard !prefix.isEmpty,
              prefix.allSatisfy(TextRules.isASCIIDigit),
              wildcards.allSatisfy({ $0 == "?" }),
              body.count <= 15,
              wildcards.count <= 4, // 10^5 already exceeds maxExpansion
              let base = Int64(TextRules.string(prefix)) else { return nil }
        var scale: Int64 = 1
        for _ in 0..<wildcards.count {
            scale *= 10
        }
        let low = base * scale
        return low...(low + scale - 1)
    }
}
