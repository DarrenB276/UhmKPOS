package com.uhmk.pos.core.money

import java.text.DecimalFormat
import kotlin.math.abs

/**
 * Money is handled everywhere as a [Long] count of centavos, never a floating point peso value.
 *
 * Doubles accumulate representation error, so a day of sales stops reconciling against the sum of
 * its lines. Integers make every total exact by construction.
 */
object Money {

    private val PLAIN = DecimalFormat("#,##0.00")

    /** 12345 -> "123.45" */
    fun formatAmount(centavos: Long): String {
        val sign = if (centavos < 0) "-" else ""
        return sign + PLAIN.format(abs(centavos) / 100.0)
    }

    /** 12345 -> "₱123.45" */
    fun format(centavos: Long, symbol: String = "₱"): String {
        val sign = if (centavos < 0) "-" else ""
        return sign + symbol + PLAIN.format(abs(centavos) / 100.0)
    }

    /**
     * Parses user input such as "99", "99.5", "₱1,234.50" into centavos.
     * Returns null when the text is not a usable amount.
     */
    fun parse(text: String): Long? {
        val cleaned = text.trim().replace(",", "").replace("₱", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        val negative = cleaned.startsWith("-")
        val body = cleaned.removePrefix("-")
        if (body.isEmpty() || body.any { !it.isDigit() && it != '.' }) return null
        if (body.count { it == '.' } > 1) return null

        val parts = body.split(".")
        val pesos = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
        val fraction = when {
            parts.size == 1 -> 0L
            else -> {
                // Pad "5" to "50" and truncate anything beyond two decimal places.
                val frac = parts[1].padEnd(2, '0').take(2)
                frac.toLongOrNull() ?: return null
            }
        }
        val total = pesos * 100 + fraction
        return if (negative) -total else total
    }

    /** Whole pesos to centavos. Used by the catalogue seeder. */
    fun fromPesos(pesos: Long): Long = pesos * 100
}
