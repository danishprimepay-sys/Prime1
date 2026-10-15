package com.spamshield.core

import java.util.Locale
import java.util.regex.Pattern

/**
 * String helpers that reproduce the Python reference exactly, so all engines agree on
 * edge cases such as non-breaking spaces or non-ASCII letters.
 */
internal object Text {
    /** Python's `str.isspace()` set. */
    fun isSpace(c: Char): Boolean = when (c) {
        ' ', '\t', '\n', '\u000B', '\u000C', '\r',
        '\u001C', '\u001D', '\u001E', '\u001F', '\u0085', ' ', ' ',
        ' ', ' ', ' ', ' ', '　' -> true
        in ' '..' ' -> true
        else -> false
    }

    fun isAsciiLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'

    fun asciiUpper(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in 'a'..'z') c - 32 else c)
    }

    fun lower(s: String): String = s.lowercase(Locale.ROOT)

    fun stripSpace(s: String): String = s.dropWhile(::isSpace).dropLastWhile(::isSpace)

    fun removeSpace(s: String): String = s.filterNot(::isSpace)

    /**
     * Compiles a spec-dialect pattern. UNIX_LINES makes `.`, `^` and `$` treat only `\n` as a
     * line terminator, matching Python's `re` (Java otherwise also stops at `\r`, U+0085, ...).
     */
    fun regex(pattern: String): Pattern = Pattern.compile(pattern, Pattern.UNIX_LINES)
}
