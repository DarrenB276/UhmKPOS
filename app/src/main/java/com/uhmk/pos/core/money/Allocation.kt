package com.uhmk.pos.core.money

/**
 * Splits a whole-sale discount across lines in proportion to their value.
 *
 * Integer division loses centavos, so the remainder goes to the largest line. That keeps the parts
 * summing to exactly [total] — without it, per-item report figures drift a centavo or two away
 * from the sale total.
 *
 * Shared by the cart preview and by the committed sale so the number on screen and the number on
 * the receipt can never disagree.
 */
fun distribute(total: Long, weights: List<Long>): List<Long> {
    val sum = weights.sum()
    if (total <= 0 || sum <= 0) return List(weights.size) { 0L }

    val capped = total.coerceAtMost(sum)
    val parts = weights.map { capped * it / sum }.toMutableList()
    val remainder = capped - parts.sum()
    if (remainder != 0L) {
        val biggest = weights.indices.maxByOrNull { weights[it] } ?: 0
        parts[biggest] = parts[biggest] + remainder
    }
    return parts
}
