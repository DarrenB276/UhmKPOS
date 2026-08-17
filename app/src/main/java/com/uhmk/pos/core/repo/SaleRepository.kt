package com.uhmk.pos.core.repo

import androidx.room.withTransaction
import com.uhmk.pos.core.db.AppDatabase
import com.uhmk.pos.core.db.AuditLogEntity
import com.uhmk.pos.core.db.CategorySalesRow
import com.uhmk.pos.core.db.DailyPoint
import com.uhmk.pos.core.db.EmployeeSalesRow
import com.uhmk.pos.core.db.ItemEntity
import com.uhmk.pos.core.db.ItemSalesRow
import com.uhmk.pos.core.db.OrderTypeSalesRow
import com.uhmk.pos.core.db.PaymentSalesRow
import com.uhmk.pos.core.db.RangeTotals
import com.uhmk.pos.core.db.SaleEntity
import com.uhmk.pos.core.db.SaleLineEntity
import com.uhmk.pos.core.db.SaleWithLines
import com.uhmk.pos.core.db.TierSalesRow
import com.uhmk.pos.core.model.CartLine
import com.uhmk.pos.core.model.OrderType
import com.uhmk.pos.core.model.PriceTier
import com.uhmk.pos.core.money.distribute
import com.uhmk.pos.core.time.DateRange
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.util.UUID

/** One row of an imported day tally, already resolved to a catalogue item and a price tier. */
data class TallyImportLine(
    val date: LocalDate,
    val itemId: String,
    val tier: PriceTier,
    val qty: Int,
)

data class TallyImportReport(
    val datesWritten: Int,
    /** Dates the file listed as having no sales, whose old tally was removed. */
    val datesCleared: Int,
    val linesWritten: Int,
    val unitsWritten: Int,
    /** Tallies that already existed on those dates and were replaced; their cloud copies must go too. */
    val replacedSaleIds: List<String>,
    val unmatchedItemIds: List<String>,
)

class SaleRepository(
    private val db: AppDatabase,
) {
    private val saleDao = db.saleDao()
    private val itemDao = db.itemDao()

    fun observeRecent(limit: Int = 100): Flow<List<SaleEntity>> = saleDao.observeRecent(limit)
    fun observeInRange(from: Long, to: Long): Flow<List<SaleEntity>> = saleDao.observeInRange(from, to)
    fun observeHistoryInRange(from: Long, to: Long): Flow<List<SaleEntity>> =
        saleDao.observeHistoryInRange(from, to)
    fun observeTotals(from: Long, to: Long): Flow<RangeTotals> = saleDao.observeTotals(from, to)
    fun observeItemBreakdown(from: Long, to: Long): Flow<List<ItemSalesRow>> =
        saleDao.observeItemBreakdown(from, to)
    fun observeCategoryBreakdown(from: Long, to: Long): Flow<List<CategorySalesRow>> =
        saleDao.observeCategoryBreakdown(from, to)
    fun observeTierBreakdown(from: Long, to: Long): Flow<List<TierSalesRow>> =
        saleDao.observeTierBreakdown(from, to)
    fun observeEmployeeBreakdown(from: Long, to: Long): Flow<List<EmployeeSalesRow>> =
        saleDao.observeEmployeeBreakdown(from, to)
    fun observePaymentBreakdown(from: Long, to: Long): Flow<List<PaymentSalesRow>> =
        saleDao.observePaymentBreakdown(from, to)
    fun observeOrderTypeBreakdown(from: Long, to: Long): Flow<List<OrderTypeSalesRow>> =
        saleDao.observeOrderTypeBreakdown(from, to)
    fun observeDailySeries(from: Long, to: Long): Flow<List<DailyPoint>> =
        saleDao.observeDailySeries(from, to)
    fun observeSale(id: String): Flow<SaleWithLines?> = saleDao.observeSaleWithLines(id)
    fun observeAuditForDate(epochDay: Long): Flow<List<AuditLogEntity>> =
        db.auditLogDao().observeForDate(epochDay)

    suspend fun saleWithLines(id: String): SaleWithLines? = saleDao.saleWithLines(id)
    suspend fun salesWithLinesInRange(from: Long, to: Long): List<SaleWithLines> =
        saleDao.salesWithLinesInRange(from, to)
    suspend fun historyWithLinesInRange(from: Long, to: Long): List<SaleWithLines> =
        saleDao.historyWithLinesInRange(from, to)
    suspend fun activeTallyForDay(from: Long, to: Long): SaleWithLines? =
        saleDao.activeTallyForDay(from, to)

    /**
     * Commits a sale and decrements stock in one transaction.
     *
     * Sale totals are derived by summing the stored lines rather than recomputed independently, so
     * the headline figures can never disagree with the per-item breakdown. Lines whose cost is
     * unknown are excluded from profit and carried separately.
     */
    suspend fun recordSale(
        lines: List<CartLine>,
        discountCentavos: Long,
        cashierId: String,
        cashierName: String,
        note: String = "",
        paymentMethod: String = "Cash",
        tenderedCentavos: Long = 0,
        orderType: OrderType = OrderType.UNSPECIFIED,
        orderLabel: String = "",
        soldAt: Long = System.currentTimeMillis(),
        source: String = "POS",
        deviceCode: String = "",
        /**
         * False when the units left the shelf before the app knew about them — an imported
         * historical day. Deducting those again would charge today's stock for last week's sales.
         */
        adjustStock: Boolean = true,
    ): Result<String> {
        if (lines.isEmpty()) return Result.failure(IllegalArgumentException("Cart is empty"))
        if (lines.any { it.qty <= 0 }) {
            return Result.failure(IllegalArgumentException("Every line needs a quantity of at least 1"))
        }

        val saleId = "sal-" + UUID.randomUUID().toString()
        val gross = lines.sumOf { it.gross }
        val discount = discountCentavos.coerceIn(0, gross)
        val shares = distribute(discount, lines.map { it.gross })

        val lineEntities = lines.mapIndexed { index, line ->
            SaleLineEntity(
                id = "sln-" + UUID.randomUUID().toString(),
                saleId = saleId,
                itemId = line.item.id,
                itemName = line.item.name,
                category = line.item.category,
                tier = line.tier,
                unitPriceCentavos = line.unitPrice,
                unitCostCentavos = line.unitCost,
                costKnown = line.costKnown,
                qty = line.qty,
                discountCentavos = shares[index],
            )
        }

        return runCatching {
            db.withTransaction {
                val sale = SaleEntity(
                    id = saleId,
                    soldAt = soldAt,
                    cashierId = cashierId,
                    cashierName = cashierName,
                    receiptNo = saleDao.nextReceiptNo(deviceCode),
                    deviceCode = deviceCode,
                    grossCentavos = lineEntities.sumOf { it.grossCentavos },
                    discountCentavos = lineEntities.sumOf { it.discountCentavos },
                    netCentavos = lineEntities.sumOf { it.netCentavos },
                    costCentavos = lineEntities.sumOf { it.costCentavos },
                    profitCentavos = lineEntities.sumOf { it.profitCentavos ?: 0L },
                    unknownCostCentavos = lineEntities
                        .filterNot { it.costKnown }
                        .sumOf { it.netCentavos },
                    paymentMethod = paymentMethod,
                    tenderedCentavos = tenderedCentavos,
                    orderType = orderType.name,
                    orderLabel = orderLabel.trim(),
                    note = note,
                    updatedAt = System.currentTimeMillis(),
                    voidedAt = null,
                    source = source,
                    dirty = true,
                )
                saleDao.insertSale(sale)
                saleDao.insertLines(lineEntities)
                // decrementStock is a no-op for service items, which never run out.
                if (adjustStock) {
                    lines.forEach { itemDao.decrementStock(it.item.id, it.qty, soldAt) }
                }
            }
            saleId
        }
    }

    /** Replaces an active day-tally receipt and records who changed what and when. */
    suspend fun saveTally(
        lines: List<CartLine>,
        soldAt: Long,
        existingSaleId: String?,
        actorId: String,
        actorName: String,
        businessDateEpochDay: Long,
        beforeSummary: String,
        afterSummary: String,
    ): Result<String> = runCatching {
        db.withTransaction {
            val newId = recordSale(
                lines = lines,
                discountCentavos = 0,
                cashierId = actorId,
                cashierName = actorName,
                note = "Day tally",
                orderLabel = "Day tally",
                soldAt = soldAt,
                source = "TALLY",
            ).getOrThrow()

            existingSaleId?.let { oldId ->
                val existing = saleDao.saleWithLines(oldId)
                if (existing != null && existing.sale.voidedAt == null && existing.sale.returnedAt == null) {
                    val now = System.currentTimeMillis()
                    existing.lines.forEach { itemDao.addStock(it.itemId, it.qty, now) }
                    saleDao.markVoided(oldId, now)
                }
            }

            db.auditLogDao().upsert(
                AuditLogEntity(
                    id = "aud-" + UUID.randomUUID(),
                    action = if (existingSaleId == null) "TALLY_CREATED" else "TALLY_EDITED",
                    entityId = newId,
                    businessDateEpochDay = businessDateEpochDay,
                    actorId = actorId,
                    actorName = actorName,
                    occurredAt = System.currentTimeMillis(),
                    beforeSummary = beforeSummary,
                    afterSummary = afterSummary,
                    dirty = true,
                )
            )
            newId
        }
    }

    /**
     * Replaces the day tally on each imported date with the rows supplied.
     *
     * Replace, not merge: a day's figures come from one source, and adding a second tally beside an
     * existing one would double that date in every report. Dates absent from the file are left
     * exactly as they are, so this can be run for one week without disturbing another.
     *
     * Prices and costs are read from the catalogue as it stands now and then snapshotted onto the
     * lines, the same as a live sale — later price changes cannot move an imported day.
     */
    suspend fun importDayTallies(
        lines: List<TallyImportLine>,
        /** Every date the file covers. Dates with no rows are cleared rather than left stale. */
        dates: List<LocalDate>,
        actorId: String,
        actorName: String,
    ): Result<TallyImportReport> = runCatching {
        val byDate = lines.filter { it.qty > 0 }.groupBy { it.date }
        val itemCache = mutableMapOf<String, ItemEntity?>()
        val replaced = mutableListOf<String>()
        val missing = sortedSetOf<String>()
        var datesWritten = 0
        var datesCleared = 0
        var linesWritten = 0
        var unitsWritten = 0

        for (date in (dates + byDate.keys).distinct().sorted()) {
            val dayRows = byDate[date].orEmpty()
            val cartLines = dayRows.mapNotNull { row ->
                val item = itemCache.getOrPut(row.itemId) { itemDao.getById(row.itemId) }
                if (item == null) {
                    missing += row.itemId
                    null
                } else {
                    CartLine(item = item, tier = row.tier, qty = row.qty)
                }
            }

            val from = DateRange.startOfDay(date)
            val to = DateRange.endOfDay(date)
            val previous = saleDao.salesOfSourceInRange("TALLY", from, to).map { it.id }

            // A day the file says had no sales: clear it and write nothing.
            if (cartLines.isEmpty()) {
                if (previous.isNotEmpty()) {
                    db.withTransaction { saleDao.deleteSales(previous) }
                    replaced += previous
                    datesCleared++
                }
                continue
            }

            db.withTransaction {
                if (previous.isNotEmpty()) saleDao.deleteSales(previous)
                recordSale(
                    lines = cartLines,
                    discountCentavos = 0,
                    cashierId = actorId,
                    cashierName = actorName,
                    note = "Day tally",
                    orderLabel = "Day tally",
                    // Midday, matching a hand-entered tally, so the row cannot drift across a
                    // date boundary when a report is read in a different time zone.
                    soldAt = from + 12 * 60 * 60 * 1000L,
                    source = "TALLY",
                    adjustStock = false,
                ).getOrThrow()

                db.auditLogDao().upsert(
                    AuditLogEntity(
                        id = "aud-" + UUID.randomUUID(),
                        action = if (previous.isEmpty()) "TALLY_IMPORTED" else "TALLY_REPLACED_BY_IMPORT",
                        entityId = date.toString(),
                        businessDateEpochDay = date.toEpochDay(),
                        actorId = actorId,
                        actorName = actorName,
                        occurredAt = System.currentTimeMillis(),
                        beforeSummary = if (previous.isEmpty()) "no tally on file"
                        else "${previous.size} tally receipt(s) removed",
                        afterSummary = "${cartLines.size} lines, ${cartLines.sumOf { it.qty }} units",
                        dirty = true,
                    )
                )
            }

            replaced += previous
            datesWritten++
            linesWritten += cartLines.size
            unitsWritten += cartLines.sumOf { it.qty }
        }

        TallyImportReport(
            datesWritten = datesWritten,
            datesCleared = datesCleared,
            linesWritten = linesWritten,
            unitsWritten = unitsWritten,
            replacedSaleIds = replaced,
            unmatchedItemIds = missing.toList(),
        )
    }

    /** Removes specific sales outright. Used to clear test receipts without touching real trade. */
    suspend fun deleteSales(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        db.withTransaction { ids.chunked(500).forEach { saleDao.deleteSales(it) } }
        return ids.size
    }

    /**
     * Wipes every sale for a clean start: completed, voided and returned alike.
     *
     * Sale lines go with them through the foreign key cascade. Stock is deliberately left alone —
     * counts have been edited by hand since those test sales, so silently adding units back would
     * be a worse guess than leaving the numbers where the user put them.
     *
     * Returns the number of sales removed.
     */
    suspend fun resetAllSales(): Int {
        val count = saleDao.countSales()
        db.withTransaction { saleDao.deleteAllSales() }
        return count
    }

    /** Voids a sale and returns its units to stock. */
    suspend fun voidSale(saleId: String): Result<Unit> = runCatching {
        db.withTransaction {
            val existing = saleDao.saleWithLines(saleId) ?: return@withTransaction
            if (existing.sale.voidedAt != null || existing.sale.returnedAt != null) {
                error("This order is already ${existing.sale.status.label.lowercase()}")
            }
            val now = System.currentTimeMillis()
            existing.lines.forEach { itemDao.addStock(it.itemId, it.qty, now) }
            saleDao.markVoided(saleId, now)
        }
    }

    /** Records a full-order return, keeps the receipt in history, and restores its inventory. */
    suspend fun returnSale(saleId: String, reason: String): Result<Unit> = runCatching {
        db.withTransaction {
            val existing = saleDao.saleWithLines(saleId) ?: error("Order not found")
            if (existing.sale.voidedAt != null || existing.sale.returnedAt != null) {
                error("This order is already ${existing.sale.status.label.lowercase()}")
            }
            val now = System.currentTimeMillis()
            existing.lines.forEach { itemDao.addStock(it.itemId, it.qty, now) }
            saleDao.markReturned(saleId, reason.trim(), now)
        }
    }
}
