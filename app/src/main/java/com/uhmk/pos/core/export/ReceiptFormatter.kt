package com.uhmk.pos.core.export

import com.uhmk.pos.core.db.SaleWithLines
import com.uhmk.pos.core.money.Money
import com.uhmk.pos.core.model.OrderType
import com.uhmk.pos.core.prefs.StoreSettings
import com.uhmk.pos.core.time.Clock

/**
 * Renders a sale as a plain-text receipt.
 *
 * Monospaced and 32 characters wide, which is the standard column count for the 58mm thermal
 * printers used at small counters — so the same text can be shared to chat or piped to a printer
 * without reformatting.
 */
object ReceiptFormatter {

    private const val WIDTH = 32

    fun render(
        sale: SaleWithLines,
        settings: StoreSettings,
        showProfit: Boolean = false,
    ): String {
        val s = sale.sale
        val currency = settings.currencySymbol
        val sb = StringBuilder()

        sb.appendLine(center(settings.storeName))
        sb.appendLine(center("Receipt ${s.receiptLabel}"))
        sb.appendLine(center(Clock.stamp(s.soldAt)))
        sb.appendLine(center("Cashier: ${s.cashierName}"))
        if (s.voidedAt != null) sb.appendLine(center("*** VOIDED ***"))
        if (s.returnedAt != null) {
            sb.appendLine(center("*** RETURNED ***"))
            if (s.returnReason.isNotBlank()) sb.appendLine(center(s.returnReason))
        }
        val orderType = OrderType.from(s.orderType)
        if (orderType != OrderType.UNSPECIFIED) {
            sb.appendLine(center(orderType.label.uppercase()))
        }
        if (s.orderLabel.isNotBlank()) {
            sb.appendLine(center(s.orderLabel))
        }
        sb.appendLine(rule())

        sale.lines.forEach { line ->
            val tierMark = if (line.tier.name == "STUDENT") " *" else ""
            sb.appendLine(line.itemName.take(WIDTH))
            sb.appendLine(
                pair(
                    "  ${line.qty} x ${Money.formatAmount(line.unitPriceCentavos)}$tierMark",
                    Money.formatAmount(line.grossCentavos),
                )
            )
            if (line.discountCentavos > 0) {
                sb.appendLine(pair("  less discount", "-" + Money.formatAmount(line.discountCentavos)))
            }
        }

        sb.appendLine(rule())
        sb.appendLine(pair("Subtotal", Money.formatAmount(s.grossCentavos)))
        if (s.discountCentavos > 0) {
            sb.appendLine(pair("Discount", "-" + Money.formatAmount(s.discountCentavos)))
        }
        sb.appendLine(pair("TOTAL", currency + Money.formatAmount(s.netCentavos)))

        if (s.tenderedCentavos > 0) {
            sb.appendLine(pair(s.paymentMethod, Money.formatAmount(s.tenderedCentavos)))
            sb.appendLine(pair("Change", Money.formatAmount(s.changeCentavos)))
        }

        if (showProfit) {
            sb.appendLine(rule())
            sb.appendLine(pair("Cost of goods", Money.formatAmount(s.costCentavos)))
            sb.appendLine(pair("Gross profit", Money.formatAmount(s.profitCentavos)))
            if (s.hasUnknownCost) {
                sb.appendLine(pair("Cost not set", Money.formatAmount(s.unknownCostCentavos)))
            }
        }

        if (sale.lines.any { it.tier.name == "STUDENT" }) {
            sb.appendLine()
            sb.appendLine("* ${settings.studentLabel} price")
        }
        if (s.note.isNotBlank()) {
            sb.appendLine()
            sb.appendLine(s.note)
        }

        sb.appendLine()
        sb.appendLine(center(settings.receiptFooter))
        return sb.toString()
    }

    private fun rule() = "-".repeat(WIDTH)

    private fun center(text: String): String {
        val t = text.take(WIDTH)
        val pad = (WIDTH - t.length) / 2
        return " ".repeat(pad.coerceAtLeast(0)) + t
    }

    /** Left label, right-aligned amount, dots of space between. */
    private fun pair(left: String, right: String): String {
        val l = left.take(WIDTH - right.length - 1)
        val gap = (WIDTH - l.length - right.length).coerceAtLeast(1)
        return l + " ".repeat(gap) + right
    }
}
