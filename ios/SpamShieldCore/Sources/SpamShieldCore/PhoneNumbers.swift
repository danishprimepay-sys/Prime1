import Foundation

/// Number normalization and caller-ID checks (SPEC §1).
public enum PhoneNumbers {
    /// Normalizes a raw caller ID / sender. Returns `nil` for "no number".
    public static func normalize(_ raw: String?, countryCode: String = "1") -> String? {
        guard let raw = raw else { return nil }
        let scalars = Array(raw.unicodeScalars)
        if scalars.allSatisfy(TextRules.isWhitespace) {
            return nil
        }
        if scalars.contains(where: TextRules.isASCIILetter) {
            return TextRules.string(
                scalars.filter { !TextRules.isWhitespace($0) }.map(TextRules.asciiUppercased)
            )
        }
        let firstNonSpace = scalars.first { !TextRules.isWhitespace($0) }
        let plus = firstNonSpace?.value == 0x2B
        let digits = TextRules.string(scalars.filter(TextRules.isASCIIDigit))
        if digits.isEmpty {
            return nil
        }
        if plus {
            return "+" + digits
        }
        if countryCode == "1" && digits.hasPrefix("011") && digits.count > 3 {
            return "+" + String(digits.dropFirst(3))
        }
        if digits.hasPrefix("00") && digits.count > 2 {
            return "+" + String(digits.dropFirst(2))
        }
        if countryCode == "1" {
            if digits.count == 10 {
                return "+1" + digits
            }
            if digits.count == 11 && digits.hasPrefix("1") {
                return "+" + digits
            }
        } else if digits.hasPrefix("0") && digits.count >= 8 {
            return "+" + countryCode + String(digits.dropFirst())
        }
        return digits
    }

    /// Whether a normalized number is plausible as a calling number.
    public static func isValidCallerNumber(_ number: String) -> Bool {
        let s = Array(number.unicodeScalars)
        guard s.first?.value == 0x2B else { return false }
        let rest = s.dropFirst()
        guard !rest.isEmpty, rest.allSatisfy(TextRules.isASCIIDigit) else { return false }
        if s.count >= 2 && s[1].value == 0x31 {
            return s.count == 12 && isTwoToNine(s[2]) && isTwoToNine(s[5])
        }
        return rest.count >= 8 && rest.count <= 15
    }

    /// Same NANP area code and exchange as the user's own (different) number.
    public static func isNeighborSpoof(_ caller: String?, _ user: String?) -> Bool {
        guard let caller = caller, let user = user else { return false }
        let c = Array(caller.unicodeScalars)
        let u = Array(user.unicodeScalars)
        guard c.count == 12, u.count == 12, isPlusOne(c), isPlusOne(u), caller != user else {
            return false
        }
        return c[2..<8].elementsEqual(u[2..<8])
    }

    /// For a normalized `+<digits>` number, its E.164 digits as an integer
    /// (the CallKit representation). `nil` for anything else.
    public static func e164Integer(_ normalized: String) -> Int64? {
        let s = Array(normalized.unicodeScalars)
        guard s.first?.value == 0x2B else { return nil }
        let digits = s.dropFirst()
        guard !digits.isEmpty, digits.count <= 15, digits.allSatisfy(TextRules.isASCIIDigit) else {
            return nil
        }
        return Int64(TextRules.string(digits))
    }

    private static func isTwoToNine(_ s: Unicode.Scalar) -> Bool {
        s.value >= 0x32 && s.value <= 0x39
    }

    private static func isPlusOne(_ s: [Unicode.Scalar]) -> Bool {
        s.count >= 2 && s[0].value == 0x2B && s[1].value == 0x31
    }
}
