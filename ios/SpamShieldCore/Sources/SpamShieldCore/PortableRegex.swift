import Foundation

/// A thin wrapper over `NSRegularExpression` configured for the spec's
/// portable regex dialect: `.` excludes only `\n`, and `^`/`$` anchor to the
/// whole string (`$` also before a final `\n`, as in Python and ICU).
public struct PortableRegex {
    private let regex: NSRegularExpression

    public init(_ pattern: String) throws {
        regex = try NSRegularExpression(pattern: pattern, options: [.useUnixLineSeparators])
    }

    private func fullRange(of text: String) -> NSRange {
        NSRange(location: 0, length: text.utf16.count)
    }

    /// True when the pattern is found anywhere in `text`.
    public func find(in text: String) -> Bool {
        regex.firstMatch(in: text, options: [], range: fullRange(of: text)) != nil
    }

    /// Replaces every non-overlapping match with the literal `replacement`.
    public func replaceAll(in text: String, with replacement: String) -> String {
        regex.stringByReplacingMatches(
            in: text,
            options: [],
            range: fullRange(of: text),
            withTemplate: NSRegularExpression.escapedTemplate(for: replacement)
        )
    }

    /// The text of capture group `group` for every non-overlapping match.
    public func captures(group: Int, in text: String) -> [String] {
        let nsText = NSString(string: text)
        return regex.matches(in: text, options: [], range: fullRange(of: text)).compactMap { match in
            let range = match.range(at: group)
            guard range.location != NSNotFound else { return nil }
            return nsText.substring(with: range)
        }
    }
}
