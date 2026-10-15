package com.spamshield.core

import java.util.regex.Matcher
import kotlin.math.exp

/** Section 5 of the spec: bag-of-words logistic regression over SMS text. */
class Classifier(private val model: ClassifierModel) {
    private val replacements = model.tokenizer.replacements.map { r ->
        Text.regex(r.pattern) to Matcher.quoteReplacement(" ${r.token} ")
    }
    private val minLength = model.tokenizer.minTokenLength

    /** Sorted unique tokens of [text]. */
    fun tokenize(text: String?): List<String> {
        var s = Text.lower(text ?: "")
        for ((pattern, replacement) in replacements) {
            s = pattern.matcher(s).replaceAll(replacement)
        }
        return splitTokens(s).filter { it.length >= minLength }.toSortedSet().toList()
    }

    fun probability(text: String?): Double {
        // Summed in sorted token order, like the reference, so rounding is identical.
        var logit = model.bias
        for (token in tokenize(text)) logit += model.weights[token] ?: 0.0
        return 1.0 / (1.0 + exp(-logit))
    }

    /** Equivalent of Python `re.split("[^a-z0-9_]+", s)`, including empty pieces. */
    private fun splitTokens(s: String): List<String> {
        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        var inSeparator = false
        for (c in s) {
            if (c in 'a'..'z' || c in '0'..'9' || c == '_') {
                current.append(c)
                inSeparator = false
            } else if (!inSeparator) {
                pieces += current.toString()
                current.clear()
                inSeparator = true
            }
        }
        pieces += current.toString()
        return pieces
    }
}
