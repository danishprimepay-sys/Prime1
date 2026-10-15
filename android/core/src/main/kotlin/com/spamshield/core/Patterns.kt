package com.spamshield.core

/** Section 2 of the spec: block/allow list entries with `*` / `?` wildcards. */
object Patterns {
    private const val STRIPPED = "()-."

    fun isWildcard(entry: String): Boolean = '*' in entry || '?' in entry

    /** Prepares a list entry for matching, or returns `null` when it can never match. */
    fun prepare(entry: String, countryCode: String = Numbers.DEFAULT_COUNTRY_CODE): String? {
        if (isWildcard(entry)) {
            val pattern = Text.asciiUpper(entry.filterNot { Text.isSpace(it) || it in STRIPPED })
            return pattern.ifEmpty { null }
        }
        return Numbers.normalize(entry, countryCode)?.ifEmpty { null }
    }

    /** Glob match over the whole [text]: `?` = one char, `*` = any run, else literal. */
    fun globMatch(pattern: String, text: String): Boolean {
        var p = 0
        var t = 0
        var star = -1
        var mark = 0
        while (t < text.length) {
            if (p < pattern.length && (pattern[p] == '?' || pattern[p] == text[t])) {
                p++
                t++
            } else if (p < pattern.length && pattern[p] == '*') {
                star = p
                mark = t
                p++
            } else if (star != -1) {
                p = star + 1
                mark++
                t = mark
            } else {
                return false
            }
        }
        while (p < pattern.length && pattern[p] == '*') p++
        return p == pattern.length
    }

    fun matches(entry: String, number: String, countryCode: String = Numbers.DEFAULT_COUNTRY_CODE): Boolean {
        val pattern = prepare(entry, countryCode) ?: return false
        return globMatch(pattern, number)
    }

    fun matchesAny(entries: Iterable<String>, number: String, countryCode: String = Numbers.DEFAULT_COUNTRY_CODE): Boolean =
        entries.any { matches(it, number, countryCode) }
}
