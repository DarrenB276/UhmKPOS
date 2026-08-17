package com.uhmk.pos.core.export

import com.uhmk.pos.core.db.ItemEntity
import com.uhmk.pos.core.model.OrderType
import com.uhmk.pos.core.model.PriceTier
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.absoluteValue

enum class ImportedReceiptStatus { COMPLETED, VOIDED, RETURNED }

data class ImportedHistoryLine(
    val itemId: String,
    val itemName: String,
    val category: String,
    val tier: PriceTier,
    val unitPriceCentavos: Long,
    val unitCostCentavos: Long,
    val costKnown: Boolean,
    val discountCentavos: Long,
)

data class ImportedHistoryReceipt(
    val id: String,
    val soldAt: Long,
    val receiptNo: Long,
    val deviceCode: String,
    val cashierName: String,
    val grossCentavos: Long,
    val discountCentavos: Long,
    val netCentavos: Long,
    val tenderedCentavos: Long,
    val paymentMethod: String,
    val orderType: OrderType,
    val orderLabel: String,
    val status: ImportedReceiptStatus,
    val lines: List<ImportedHistoryLine>,
)

data class SalesHistoryImportPlan(
    val receipts: List<ImportedHistoryReceipt>,
    val summaryRows: Int,
    val unitsSold: Int,
    val completedReceipts: Int,
    val voidedReceipts: Int,
    val returnedReceipts: Int,
    val grossCentavos: Long,
    val discountCentavos: Long,
    val refundCentavos: Long,
    val netAfterRefundsCentavos: Long,
    val firstSoldAt: Long,
    val lastSoldAt: Long,
)

/**
 * Rebuilds dated receipt history from a receipt export and verifies it against an item summary.
 *
 * The item summary has no sale date, so it cannot populate Day Tally by itself. Receipt descriptions
 * contain the same product quantities and a timestamp; those rows are the dated source of truth.
 * The summary is an integrity check that prevents a partial or mismatched pair of files from being
 * committed.
 */
object SalesHistoryCsvImporter {

    private val receiptDate = DateTimeFormatter.ofPattern("M/d/yy h:mm a", Locale.US)
    private val storeZone = ZoneId.of("Asia/Manila")
    private val linePattern = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*x\\s*(.+)$", RegexOption.IGNORE_CASE)

    private data class SummaryRow(
        val sourceName: String,
        val itemsSold: Int,
        val grossCentavos: Long,
        val itemsRefunded: Int,
        val refundCentavos: Long,
        val discountCentavos: Long,
        val netCentavos: Long,
    )

    private data class SourceLine(val sourceName: String, val qty: Int)

    private data class ReceiptRow(
        val soldAt: Long,
        val receiptNumber: String,
        val receiptType: String,
        val grossCentavos: Long,
        val discountCentavos: Long,
        val netCentavos: Long,
        val tenderedCentavos: Long,
        val paymentType: String,
        val diningOption: String,
        val cashierName: String,
        val status: String,
        val lines: List<SourceLine>,
    ) {
        val importStatus: ImportedReceiptStatus
            get() = when {
                status.equals("cancelled", ignoreCase = true) -> ImportedReceiptStatus.VOIDED
                receiptType.equals("refund", ignoreCase = true) -> ImportedReceiptStatus.RETURNED
                else -> ImportedReceiptStatus.COMPLETED
            }
    }

    fun plan(contents: List<String>, catalogue: List<ItemEntity>): SalesHistoryImportPlan {
        require(contents.size >= 2) { "Select the receipt export and item-sales summary together." }

        var summaryTable: List<List<String>>? = null
        var receiptTable: List<List<String>>? = null
        contents.forEach { raw ->
            val table = CsvTable.parse(raw.removePrefix("\uFEFF"))
                .filter { row -> row.any(String::isNotBlank) }
            if (table.isEmpty()) return@forEach
            val headers = table.first().map(CsvTable::normaliseHeader)
            when {
                setOf("itemname", "itemssold", "grosssales", "itemsrefunded").all(headers::contains) ->
                    summaryTable = table
                setOf("date", "receiptnumber", "receipttype", "description", "status").all(headers::contains) ->
                    receiptTable = table
            }
        }

        val summaries = parseSummary(summaryTable ?: error("The item-sales summary was not selected."))
        val receipts = parseReceipts(receiptTable ?: error("The receipt export was not selected."))
        require(summaries.isNotEmpty()) { "The item-sales summary has no product rows." }
        require(receipts.isNotEmpty()) { "The receipt export has no receipt rows." }

        reconcile(summaries, receipts)

        val resolver = HistoricalItemResolver(catalogue)
        val missing = (summaries.map { it.sourceName } + receipts.flatMap { row -> row.lines.map { it.sourceName } })
            .distinct()
            .filter { resolver.resolve(it) == null }
            .sorted()
        require(missing.isEmpty()) {
            "${missing.size} historical product(s) do not match inventory: ${missing.take(5).joinToString()}"
        }

        val summaryByName = summaries.associateBy { sourceKey(it.sourceName) }
        val imported = receipts.map { row ->
            val expanded = row.lines.flatMap { source -> List(source.qty) { source.sourceName } }
            require(expanded.isNotEmpty()) { "Receipt ${row.receiptNumber} has no product lines." }
            val weights = expanded.map { name ->
                val summary = summaryByName[sourceKey(name)]
                if (summary != null && summary.itemsSold > 0 && summary.grossCentavos > 0) {
                    (summary.grossCentavos * 1_000L / summary.itemsSold).coerceAtLeast(1L)
                } else {
                    resolver.resolve(name)!!.item.priceFor(resolver.resolve(name)!!.tier).coerceAtLeast(1L)
                }
            }
            val unitGross = allocateExact(row.grossCentavos.absoluteValue, weights)
            val unitDiscount = allocateExact(row.discountCentavos.absoluteValue, unitGross)
            val lines = expanded.mapIndexed { index, name ->
                val resolved = resolver.resolve(name)!!
                ImportedHistoryLine(
                    itemId = resolved.item.id,
                    // Preserve the original label on the receipt while itemId keeps Day Tally
                    // attached to the current catalogue product.
                    itemName = name,
                    category = resolved.item.category,
                    tier = resolved.tier,
                    unitPriceCentavos = unitGross[index],
                    unitCostCentavos = resolved.item.costCentavos,
                    costKnown = resolved.item.costKnown,
                    discountCentavos = unitDiscount[index],
                )
            }
            val gross = lines.sumOf { it.unitPriceCentavos }
            val discount = lines.sumOf { it.discountCentavos }
            require(gross == row.grossCentavos.absoluteValue && gross - discount == row.netCentavos.absoluteValue) {
                "Receipt ${row.receiptNumber} does not reconcile after allocating its product lines."
            }

            val receiptParts = row.receiptNumber.split('-', limit = 2)
            val deviceCode = receiptParts.getOrNull(0).orEmpty().takeIf { receiptParts.size == 2 }.orEmpty()
            val receiptNo = receiptParts.lastOrNull()?.filter(Char::isDigit)?.toLongOrNull()
                ?: error("Receipt ${row.receiptNumber} has no usable number.")
            val orderType = when (normalise(row.diningOption)) {
                "dine in", "dinein" -> OrderType.DINE_IN
                "takeout", "take out" -> OrderType.TAKEOUT
                else -> OrderType.UNSPECIFIED
            }
            ImportedHistoryReceipt(
                id = "imp-" + sha256(row.receiptNumber).take(24),
                soldAt = row.soldAt,
                receiptNo = receiptNo,
                deviceCode = deviceCode,
                cashierName = row.cashierName.ifBlank { "Owner" },
                grossCentavos = gross,
                discountCentavos = discount,
                netCentavos = gross - discount,
                tenderedCentavos = row.tenderedCentavos.absoluteValue,
                paymentMethod = normalisePayment(row.paymentType),
                orderType = orderType,
                orderLabel = if (orderType == OrderType.UNSPECIFIED) row.diningOption.trim() else "",
                status = row.importStatus,
                lines = lines,
            )
        }.sortedBy { it.soldAt }

        val completed = imported.filter { it.status == ImportedReceiptStatus.COMPLETED }
        val returned = imported.filter { it.status == ImportedReceiptStatus.RETURNED }
        return SalesHistoryImportPlan(
            receipts = imported,
            summaryRows = summaries.size,
            unitsSold = summaries.sumOf { it.itemsSold },
            completedReceipts = completed.size,
            voidedReceipts = imported.count { it.status == ImportedReceiptStatus.VOIDED },
            returnedReceipts = returned.size,
            grossCentavos = completed.sumOf { it.grossCentavos },
            discountCentavos = completed.sumOf { it.discountCentavos },
            refundCentavos = returned.sumOf { it.netCentavos },
            netAfterRefundsCentavos = completed.sumOf { it.netCentavos } - returned.sumOf { it.netCentavos },
            firstSoldAt = imported.minOf { it.soldAt },
            lastSoldAt = imported.maxOf { it.soldAt },
        )
    }

    private fun parseSummary(table: List<List<String>>): List<SummaryRow> {
        val headers = table.first().map(CsvTable::normaliseHeader)
        return table.drop(1).filter { it.any(String::isNotBlank) }.mapIndexed { index, cells ->
            val row = row(headers, cells)
            val name = row["itemname"].orEmpty().trim()
            require(name.isNotBlank()) { "Item summary row ${index + 2} has no product name." }
            SummaryRow(
                sourceName = name,
                itemsSold = quantity(row["itemssold"], "item summary row ${index + 2}"),
                grossCentavos = money(row["grosssales"], "item summary row ${index + 2}"),
                itemsRefunded = quantity(row["itemsrefunded"], "item summary row ${index + 2}"),
                refundCentavos = money(row["refunds"], "item summary row ${index + 2}").absoluteValue,
                discountCentavos = money(row["discounts"], "item summary row ${index + 2}").absoluteValue,
                netCentavos = money(row["netsales"], "item summary row ${index + 2}"),
            )
        }
    }

    private fun parseReceipts(table: List<List<String>>): List<ReceiptRow> {
        val headers = table.first().map(CsvTable::normaliseHeader)
        return table.drop(1).filter { it.any(String::isNotBlank) }.mapIndexed { index, cells ->
            val row = row(headers, cells)
            val label = "receipt row ${index + 2}"
            val dateText = row["date"].orEmpty().trim().replace('\u202f', ' ').replace('\u00a0', ' ')
            val soldAt = runCatching {
                LocalDateTime.parse(dateText, receiptDate).atZone(storeZone).toInstant().toEpochMilli()
            }.getOrElse { error("$label has an unreadable date: $dateText") }
            val description = row["description"].orEmpty().trim()
            val sourceLines = description.split(", ").map { part ->
                val match = linePattern.matchEntire(part.trim())
                    ?: error("$label has an unreadable product line: $part")
                val qty = BigDecimal(match.groupValues[1]).setScale(0, RoundingMode.UNNECESSARY).intValueExact()
                require(qty > 0) { "$label has a zero product quantity." }
                SourceLine(match.groupValues[2].trim(), qty)
            }
            ReceiptRow(
                soldAt = soldAt,
                receiptNumber = row["receiptnumber"].orEmpty().trim(),
                receiptType = row["receipttype"].orEmpty().trim(),
                grossCentavos = money(row["grosssales"], label),
                discountCentavos = money(row["discounts"], label).absoluteValue,
                netCentavos = money(row["netsales"], label),
                tenderedCentavos = money(row["totalcollected"], label),
                paymentType = row["paymenttype"].orEmpty().trim(),
                diningOption = row["diningoption"].orEmpty().trim(),
                cashierName = row["cashiername"].orEmpty().trim(),
                status = row["status"].orEmpty().trim(),
                lines = sourceLines,
            )
        }
    }

    private fun reconcile(summary: List<SummaryRow>, receipts: List<ReceiptRow>) {
        val sales = receipts.filter { it.importStatus == ImportedReceiptStatus.COMPLETED }
        val refunds = receipts.filter { it.importStatus == ImportedReceiptStatus.RETURNED }
        val soldByName = sales.flatMap { it.lines }.groupBy { sourceKey(it.sourceName) }
            .mapValues { (_, lines) -> lines.sumOf { it.qty } }
        val refundedByName = refunds.flatMap { it.lines }.groupBy { sourceKey(it.sourceName) }
            .mapValues { (_, lines) -> lines.sumOf { it.qty } }

        val mismatches = summary.filter { row ->
            soldByName[sourceKey(row.sourceName)].orZero() != row.itemsSold ||
                refundedByName[sourceKey(row.sourceName)].orZero() != row.itemsRefunded
        }
        require(mismatches.isEmpty()) {
            "The two files do not cover the same product quantities (${mismatches.size} mismatch(es))."
        }
        require(soldByName.keys == summary.map { sourceKey(it.sourceName) }.toSet()) {
            "The receipt export contains products that are absent from the item-sales summary."
        }

        val summaryGross = summary.sumOf { it.grossCentavos }
        val summaryDiscount = summary.sumOf { it.discountCentavos }
        val summaryRefund = summary.sumOf { it.refundCentavos }
        val summaryNet = summary.sumOf { it.netCentavos }
        val salesGross = sales.sumOf { it.grossCentavos }
        val salesDiscount = sales.sumOf { it.discountCentavos }
        val refundNet = refunds.sumOf { it.netCentavos.absoluteValue }
        require(summaryGross == salesGross && summaryDiscount == salesDiscount && summaryRefund == refundNet) {
            "The two files do not reconcile. Export both files for the same date range and try again."
        }
        require(summaryNet == salesGross - salesDiscount - refundNet) {
            "The item-sales summary net total does not reconcile with its receipts and refunds."
        }
    }

    /** Splits an exact total over arbitrary weights without capping the total at the weight sum. */
    internal fun allocateExact(total: Long, weights: List<Long>): List<Long> {
        require(total >= 0) { "Cannot allocate a negative amount." }
        if (weights.isEmpty()) return emptyList()
        val safeWeights = weights.map { it.coerceAtLeast(0L) }
        val sum = safeWeights.sum()
        if (sum <= 0L) {
            val base = total / weights.size
            var remainder = total % weights.size
            return weights.indices.map {
                base + if (remainder-- > 0) 1 else 0
            }
        }
        val result = safeWeights.map { total * it / sum }.toMutableList()
        var remainder = total - result.sum()
        safeWeights.indices.sortedByDescending { safeWeights[it] }.forEach { index ->
            if (remainder <= 0) return@forEach
            result[index]++
            remainder--
        }
        // The floor remainder is always smaller than the number of weights, but keep this robust
        // if the implementation above changes later.
        var index = 0
        while (remainder > 0) {
            result[index % result.size]++
            index++
            remainder--
        }
        return result
    }

    private fun row(headers: List<String>, cells: List<String>): Map<String, String> =
        headers.mapIndexed { index, header -> header to cells.getOrElse(index) { "" } }.toMap()

    private fun quantity(raw: String?, label: String): Int = runCatching {
        BigDecimal(raw.orEmpty().trim()).setScale(0, RoundingMode.UNNECESSARY).intValueExact()
    }.getOrElse { error("$label has an invalid quantity: ${raw.orEmpty()}") }

    private fun money(raw: String?, label: String): Long = runCatching {
        BigDecimal(raw.orEmpty().trim().replace(",", ""))
            .movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }.getOrElse { error("$label has an invalid amount: ${raw.orEmpty()}") }

    private fun normalisePayment(raw: String): String = when (normalise(raw)) {
        "gcash" -> "GCash"
        "bpi bank", "bpi" -> "BPI"
        "gotyme" -> "GoTyme"
        "cash" -> "Cash"
        "card" -> "Card"
        else -> raw.trim().ifBlank { "Other" }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun sourceKey(value: String): String = normalise(value).replace(Regex("[^a-z0-9]"), "")
    private fun normalise(value: String): String = value.trim().lowercase(Locale.US)
        .replace(Regex("\\s+"), " ")
    private fun Int?.orZero(): Int = this ?: 0
}

private data class ResolvedHistoricalItem(val item: ItemEntity, val tier: PriceTier)

/** Resolves old separate student-price products onto the app's two-price catalogue. */
private class HistoricalItemResolver(catalogue: List<ItemEntity>) {
    private val byName = catalogue.groupBy { key(it.name) }

    fun resolve(sourceName: String): ResolvedHistoricalItem? {
        val normal = normal(sourceName)
        val tier = if (normal.startsWith("stu ") || normal.endsWith(" student")) {
            PriceTier.STUDENT
        } else {
            PriceTier.REGULAR
        }
        val base = normal.removePrefix("stu ").removeSuffix(" student").trim()
        val target = aliases[base] ?: base
        val item = byName[key(target)]?.singleOrNull() ?: return null
        return ResolvedHistoricalItem(item, tier)
    }

    private fun normal(value: String): String = value.trim().lowercase(Locale.US)
        .replace("&", "and")
        .replace(Regex("\\s+"), " ")

    private fun key(value: String): String = normal(value).replace(Regex("[^a-z0-9]"), "")

    companion object {
        private val aliases = mapOf(
            "bottled water 330ml" to "wilkins pure bottled water",
            "cafe bene peach 190ml" to "caffe bene peach ice tea 190ml",
            "caffe bene greengrape 190ml" to "caffe bene greengrape",
            "enoki mushroom in economy pack" to "enoki mushroom eco pack",
            "fish cheese tofu 2pcs" to "fish cheese tofu",
            "ice cup 16oz" to "ice cup",
            "samyang cheese extra" to "samyang buldak cheese extra",
            "teokbokki with cheese 180g" to "teokbokki with cheese",
            "cheese ice cream" to "special uhm k ice cream",
            "choco ice cream" to "special uhm k ice cream",
            "melon ice cream" to "special uhm k ice cream",
            "taro ice cream" to "special uhm k ice cream",
            "hs fried rice" to "hungarian rice meal",
            "hs furikake rice" to "hungarian rice meal",
            "hs kimchi rice" to "hungarian rice meal",
            "ls furikake rice" to "luncheon meat rice meal",
            "ls kimchi rice" to "luncheon meat rice meal",
        )
    }
}
