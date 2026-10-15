import Foundation

/// Character-level helpers that mirror the Python reference exactly. All
/// string processing in the engine works on Unicode scalars (code points), the
/// unit Python's `str` uses, rather than on grapheme clusters.
enum TextRules {
    /// Python's `str.isspace()` set, so trimming/splitting agrees with the reference.
    private static let whitespace: Set<UInt32> = [
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x85, 0xA0,
        0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
        0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000,
    ]

    static func isWhitespace(_ s: Unicode.Scalar) -> Bool {
        whitespace.contains(s.value)
    }

    static func isASCIIDigit(_ s: Unicode.Scalar) -> Bool {
        s.value >= 0x30 && s.value <= 0x39
    }

    static func isASCIILetter(_ s: Unicode.Scalar) -> Bool {
        (s.value >= 0x41 && s.value <= 0x5A) || (s.value >= 0x61 && s.value <= 0x7A)
    }

    /// `[a-z0-9_]` — the classifier's token alphabet.
    static func isTokenCharacter(_ s: Unicode.Scalar) -> Bool {
        (s.value >= 0x61 && s.value <= 0x7A) || isASCIIDigit(s) || s.value == 0x5F
    }

    static func asciiUppercased(_ s: Unicode.Scalar) -> Unicode.Scalar {
        guard s.value >= 0x61 && s.value <= 0x7A, let upper = Unicode.Scalar(s.value - 32) else { return s }
        return upper
    }

    static func string<S: Sequence>(_ scalars: S) -> String where S.Element == Unicode.Scalar {
        var result = ""
        result.unicodeScalars.append(contentsOf: scalars)
        return result
    }
}
