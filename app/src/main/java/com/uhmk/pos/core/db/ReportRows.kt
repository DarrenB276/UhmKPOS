package com.uhmk.pos.core.db

import androidx.room.Embedded
import androidx.room.Relation

/**
 * Headline figures for a date range.
 *
 * [profit] counts only lines whose cost was known. Anything sold without a cost on file is kept
 * apart in [unknownNet] rather than folded in, because counting it would report the whole selling
 * price as take-home.
 */
data class RangeTotals(
    val saleCount: Int = 0,
    val itemsSold: Int = 0,
    val gross: Long = 0,
    val discount: Long = 0,
    val net: Long = 0,
    val cost: Long = 0,
    val profit: Long = 0,
    val unknownQty: Int = 0,
    val unknownNet: Long = 0,
) {
    val hasUnknownCost: Boolean get() = unknownQty > 0

    /** Margin on the portion we can actually account for. */
    val marginPercent: Double?
        get() {
            val accounted = net - unknownNet
            return if (accounted > 0) profit * 100.0 / accounted else null
        }
}

/** One row of the per-item profit breakdown. */
data class ItemSalesRow(
    val itemId: String,
    val itemName: String,
    val category: String,
    val qtySold: Int,
    val gross: Long,
    val discount: Long,
    val net: Long,
    val cost: Long,
    val profit: Long,
    val unknownQty: Int,
    val unknownNet: Long,
) {
    val costKnown: Boolean get() = unknownQty == 0
}

/** Sales rolled up by category. */
data class CategorySalesRow(
    val category: String,
    val qtySold: Int,
    val net: Long,
    val cost: Long,
    val profit: Long,
    val unknownNet: Long,
)

/** Sales split by which price tier was rung up. */
data class TierSalesRow(
    val tier: String,
    val qtySold: Int,
    val net: Long,
    val profit: Long,
)

/** Sales grouped by the cashier who recorded them. */
data class EmployeeSalesRow(
    val cashierId: String,
    val cashierName: String,
    val saleCount: Int,
    val itemsSold: Int,
    val net: Long,
    val cost: Long,
    val profit: Long,
    val unknownNet: Long,
)

/** Sales grouped by Cash, GCash, or another payment label. */
data class PaymentSalesRow(
    val paymentMethod: String,
    val saleCount: Int,
    val itemsSold: Int,
    val net: Long,
    val profit: Long,
)

/** Sales split between dine-in, takeout, and older receipts without an order type. */
data class OrderTypeSalesRow(
    val orderType: String,
    val saleCount: Int,
    val itemsSold: Int,
    val net: Long,
    val profit: Long,
)

/** A single day's totals, for the trend strip on the reports screen. */
data class DailyPoint(
    val day: String,
    val net: Long,
    val profit: Long,
)

/** How many items sit in each category, for the category manager. */
data class CategoryCount(
    val category: String,
    val itemCount: Int,
)

data class SaleWithLines(
    @Embedded val sale: SaleEntity,
    @Relation(parentColumn = "id", entityColumn = "saleId")
    val lines: List<SaleLineEntity>,
)

data class TicketWithLines(
    @Embedded val ticket: TicketEntity,
    @Relation(parentColumn = "id", entityColumn = "ticketId")
    val lines: List<TicketLineEntity>,
)
