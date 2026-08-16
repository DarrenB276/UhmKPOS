package com.uhmk.pos.core.model

import com.uhmk.pos.core.db.ItemEntity
import com.uhmk.pos.core.money.distribute

/** One pending line in the till, before the sale is committed. */
data class CartLine(
    val item: ItemEntity,
    val tier: PriceTier = PriceTier.STUDENT,
    val qty: Int = 1,
) {
    val unitPrice: Long get() = item.priceFor(tier)
    val unitCost: Long get() = item.costCentavos
    val costKnown: Boolean get() = item.costKnown
    val gross: Long get() = unitPrice * qty
    val cost: Long get() = if (costKnown) unitCost * qty else 0
}

/** The whole till: lines plus any sale-level discount. */
data class Cart(
    val lines: List<CartLine> = emptyList(),
    val discount: Long = 0,
    val note: String = "",
    val tendered: Long = 0,
    val paymentMethod: String = "Cash",
    val orderType: OrderType = OrderType.DINE_IN,
    val orderLabel: String = "",
) {
    val isEmpty: Boolean get() = lines.isEmpty()
    val itemCount: Int get() = lines.sumOf { it.qty }
    val gross: Long get() = lines.sumOf { it.gross }

    /** Discount can never exceed the bill. */
    val effectiveDiscount: Long get() = discount.coerceIn(0, gross)
    val net: Long get() = gross - effectiveDiscount

    /** Discount shares, computed exactly as they will be stored on the sale. */
    private val shares: List<Long>
        get() = distribute(effectiveDiscount, lines.map { it.gross })

    /** Capital tied up in the goods, counting only lines with a known cost. */
    val cost: Long get() = lines.sumOf { it.cost }

    /** Take-home from lines whose cost is known. */
    val profit: Long
        get() = lines.withIndex().sumOf { (i, line) ->
            if (line.costKnown) line.gross - shares[i] - line.cost else 0L
        }

    /** Revenue from lines with no cost on file — profit on these is not yet knowable. */
    val unknownNet: Long
        get() = lines.withIndex().sumOf { (i, line) ->
            if (line.costKnown) 0L else line.gross - shares[i]
        }

    val hasUnknownCost: Boolean get() = lines.any { !it.costKnown }
    val change: Long get() = (tendered - net).coerceAtLeast(0)
    val canCheckout: Boolean get() = paymentMethod != "Cash" || tendered >= net

    fun lineFor(itemId: String): CartLine? = lines.firstOrNull { it.item.id == itemId }
}
