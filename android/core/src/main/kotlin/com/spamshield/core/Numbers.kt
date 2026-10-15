package com.spamshield.core

/** Section 1 of the spec: caller ID / sender normalization. */
object Numbers {
    const val DEFAULT_COUNTRY_CODE = "1"

    /** Returns the normalized address, or `null` for "no number" (hidden caller). */
    fun normalize(raw: String?, countryCode: String = DEFAULT_COUNTRY_CODE): String? {
        if (raw == null || Text.stripSpace(raw).isEmpty()) return null
        if (raw.any(Text::isAsciiLetter)) return Text.asciiUpper(Text.removeSpace(raw))

        val plus = raw.dropWhile(Text::isSpace).startsWith("+")
        val digits = raw.filter(Text::isAsciiDigit)
        if (digits.isEmpty()) return null
        if (plus) return "+$digits"
        if (countryCode == "1" && digits.startsWith("011") && digits.length > 3) return "+" + digits.substring(3)
        if (digits.startsWith("00") && digits.length > 2) return "+" + digits.substring(2)
        if (countryCode == "1") {
            if (digits.length == 10) return "+1$digits"
            if (digits.length == 11 && digits.startsWith("1")) return "+$digits"
        } else if (digits.startsWith("0") && digits.length >= 8) {
            return "+" + countryCode + digits.substring(1)
        }
        return digits
    }

    fun isValidCallerNumber(n: String): Boolean {
        if (!n.startsWith("+")) return false
        val rest = n.substring(1)
        if (rest.isEmpty() || !rest.all(Text::isAsciiDigit)) return false
        if (n.startsWith("+1")) {
            return n.length == 12 && n[2] in '2'..'9' && n[5] in '2'..'9'
        }
        return rest.length in 8..15
    }

    fun isNeighborSpoof(caller: String?, user: String?): Boolean {
        if (caller == null || user == null) return false
        return caller.startsWith("+1") && user.startsWith("+1") &&
            caller.length == 12 && user.length == 12 &&
            caller != user && caller.substring(2, 8) == user.substring(2, 8)
    }
}
