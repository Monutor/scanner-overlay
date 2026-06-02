package com.scanner.overlay.scanner

object BarcodeShape {
    const val PREFIX = "STL"
    const val TOTAL_LENGTH = 15
    private const val DIGITS_LENGTH = 12
    private val CANONICAL_REGEX = Regex("^STL\\d{12}$")

    fun isCanonical(code: String): Boolean = CANONICAL_REGEX.matches(code)

    fun bestCanonical(code: String): String? {
        if (code.isBlank()) return null
        if (isCanonical(code)) return code

        val upper = code.uppercase().filter { it.isLetterOrDigit() }
        if (upper.isEmpty()) return null

        val stlIdx = upper.indexOf(PREFIX)
        if (stlIdx >= 0) {
            val digitsAfterStl = upper.drop(stlIdx + PREFIX.length).takeWhile { it.isDigit() }
            if (digitsAfterStl.length >= DIGITS_LENGTH) {
                return PREFIX + digitsAfterStl.take(DIGITS_LENGTH)
            }
            return null
        }

        if (upper.none { it.isLetter() }) return null

        val digitsOnly = upper.filter { it.isDigit() }
        if (digitsOnly.length >= DIGITS_LENGTH) {
            return PREFIX + digitsOnly.take(DIGITS_LENGTH)
        }
        return null
    }
}
