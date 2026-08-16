package com.uhmk.pos.core.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.uhmk.pos.core.db.CategorySalesRow
import com.uhmk.pos.core.db.ItemEntity
import com.uhmk.pos.core.db.ItemSalesRow
import com.uhmk.pos.core.db.SaleWithLines
import com.uhmk.pos.core.money.Money
import com.uhmk.pos.core.time.Clock
import com.uhmk.pos.core.time.DateRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Builds CSV files and hands them to the system share sheet.
 *
 * Files are written to `cacheDir/exports` and shared through a FileProvider, so no storage
 * permission is needed on any supported Android version.
 */
object CsvExporter {

    private const val AUTHORITY_SUFFIX = ".fileprovider"
    private const val UNKNOWN = "not set"

    /** One row per sale line — the full audit trail. */
    fun salesCsv(sales: List<SaleWithLines>): String {
        val sb = StringBuilder()
        sb.appendLine(
            row(
                "Receipt", "Sale ID", "Date", "Time", "Cashier", "Order type", "Order label",
                "Item", "Category", "Price tier",
                "Qty", "Unit price", "Unit cost", "Line total", "Line discount", "Line net",
                "Line capital", "Line profit", "Payment", "Note",
            )
        )
        sales.sortedBy { it.sale.soldAt }.forEach { (sale, lines) ->
            val date = DateRange.toLocalDate(sale.soldAt).toString()
            val time = Clock.timeOnly(sale.soldAt)
            lines.forEach { l ->
                sb.appendLine(
                    row(
                        sale.receiptLabel, sale.id, date, time, sale.cashierName,
                        sale.orderType, sale.orderLabel,
                        l.itemName, l.category, l.tier.name, l.qty.toString(),
                        Money.formatAmount(l.unitPriceCentavos),
                        if (l.costKnown) Money.formatAmount(l.unitCostCentavos) else UNKNOWN,
                        Money.formatAmount(l.grossCentavos),
                        Money.formatAmount(l.discountCentavos),
                        Money.formatAmount(l.netCentavos),
                        if (l.costKnown) Money.formatAmount(l.costCentavos) else UNKNOWN,
                        l.profitCentavos?.let { Money.formatAmount(it) } ?: UNKNOWN,
                        sale.paymentMethod, sale.note,
                    )
                )
            }
        }
        return sb.toString()
    }

    /** Completed, voided, and returned receipts, with one row per product line. */
    fun orderHistoryCsv(sales: List<SaleWithLines>): String {
        val sb = StringBuilder()
        sb.appendLine(
            row(
                "Receipt", "Status", "Source", "Sale ID", "Date", "Time", "Cashier",
                "Order type", "Order label", "Payment", "Receipt gross", "Receipt discount",
                "Receipt total", "Tendered", "Change", "Item", "Category", "Price tier", "Qty",
                "Unit price", "Unit cost", "Line net", "Line profit", "Order note",
                "Return reason", "Voided at", "Returned at",
            )
        )
        sales.sortedBy { it.sale.soldAt }.forEach { (sale, lines) ->
            val date = DateRange.toLocalDate(sale.soldAt).toString()
            val time = Clock.timeOnly(sale.soldAt)
            lines.forEach { line ->
                sb.appendLine(
                    row(
                        sale.receiptLabel, sale.status.label, sale.source, sale.id, date, time,
                        sale.cashierName, sale.orderType, sale.orderLabel, sale.paymentMethod,
                        Money.formatAmount(sale.grossCentavos), Money.formatAmount(sale.discountCentavos),
                        Money.formatAmount(sale.netCentavos), Money.formatAmount(sale.tenderedCentavos),
                        Money.formatAmount(sale.changeCentavos), line.itemName, line.category,
                        line.tier.name, line.qty.toString(), Money.formatAmount(line.unitPriceCentavos),
                        if (line.costKnown) Money.formatAmount(line.unitCostCentavos) else UNKNOWN,
                        Money.formatAmount(line.netCentavos),
                        line.profitCentavos?.let(Money::formatAmount) ?: UNKNOWN,
                        sale.note, sale.returnReason,
                        sale.voidedAt?.let(Clock::stamp).orEmpty(),
                        sale.returnedAt?.let(Clock::stamp).orEmpty(),
                    )
                )
            }
        }
        return sb.toString()
    }

    /** Per-item totals for a date range — the "what did each product earn me" view. */
    fun itemBreakdownCsv(rows: List<ItemSalesRow>, rangeLabel: String): String {
        val sb = StringBuilder()
        sb.appendLine(row("Report", "Profit by item", rangeLabel))
        sb.appendLine()
        sb.appendLine(
            row("Item", "Category", "Qty sold", "Gross", "Discount", "Revenue",
                "Capital", "Profit", "Revenue with unknown cost")
        )
        rows.forEach { r ->
            sb.appendLine(
                row(
                    r.itemName, r.category, r.qtySold.toString(),
                    Money.formatAmount(r.gross), Money.formatAmount(r.discount),
                    Money.formatAmount(r.net), Money.formatAmount(r.cost),
                    if (r.costKnown) Money.formatAmount(r.profit) else UNKNOWN,
                    Money.formatAmount(r.unknownNet),
                )
            )
        }
        sb.appendLine()
        sb.appendLine(
            row(
                "TOTAL", "", rows.sumOf { it.qtySold }.toString(),
                Money.formatAmount(rows.sumOf { it.gross }),
                Money.formatAmount(rows.sumOf { it.discount }),
                Money.formatAmount(rows.sumOf { it.net }),
                Money.formatAmount(rows.sumOf { it.cost }),
                Money.formatAmount(rows.sumOf { it.profit }),
                Money.formatAmount(rows.sumOf { it.unknownNet }),
            )
        )
        return sb.toString()
    }

    fun categoryBreakdownCsv(rows: List<CategorySalesRow>, rangeLabel: String): String {
        val sb = StringBuilder()
        sb.appendLine(row("Report", "Sales by category", rangeLabel))
        sb.appendLine()
        sb.appendLine(row("Category", "Qty sold", "Revenue", "Capital", "Profit", "Unknown cost revenue"))
        rows.forEach { r ->
            sb.appendLine(
                row(
                    r.category, r.qtySold.toString(), Money.formatAmount(r.net),
                    Money.formatAmount(r.cost), Money.formatAmount(r.profit),
                    Money.formatAmount(r.unknownNet),
                )
            )
        }
        return sb.toString()
    }

    /** The catalogue, including which items still need a cost. */
    fun inventoryCsv(items: List<ItemEntity>): String {
        val sb = StringBuilder()
        sb.appendLine(
            row(
                "SKU", "Item", "Category", "Unit cost", "Student price", "Regular price",
                "Student profit", "Regular profit", "In stock", "Warn at", "Tracks stock",
                "Stock capital", "Active",
            )
        )
        items.forEach { i ->
            sb.appendLine(
                row(
                    i.sku, i.name, i.category,
                    if (i.costKnown) Money.formatAmount(i.costCentavos) else UNKNOWN,
                    Money.formatAmount(i.studentCentavos),
                    Money.formatAmount(i.regularCentavos),
                    i.studentProfit?.let { Money.formatAmount(it) } ?: UNKNOWN,
                    i.regularProfit?.let { Money.formatAmount(it) } ?: UNKNOWN,
                    if (i.trackStock) i.stockQty.toString() else "n/a",
                    if (i.trackStock) i.lowStockAt.toString() else "n/a",
                    if (i.trackStock) "yes" else "no",
                    if (i.costKnown) Money.formatAmount(i.stockQty * i.costCentavos) else UNKNOWN,
                    if (i.active) "yes" else "no",
                )
            )
        }
        return sb.toString()
    }

    suspend fun writeAndShare(
        context: Context,
        content: String,
        baseName: String,
        mime: String = "text/csv",
        extension: String = "csv",
    ): Result<Intent> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }

            val file = File(dir, "$baseName-${Clock.fileStamp()}.$extension")
            // Excel needs the BOM to read ₱ and other non-ASCII text as UTF-8.
            file.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + content.toByteArray())

            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + AUTHORITY_SUFFIX,
                file,
            )

            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun row(vararg cells: String): String = cells.joinToString(",") { escape(it) }

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
