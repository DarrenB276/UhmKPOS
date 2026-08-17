package com.uhmk.pos.core.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {

    @Query("SELECT * FROM items ORDER BY sortIndex ASC, name ASC")
    fun observeAll(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE active = 1 ORDER BY sortIndex ASC, name ASC")
    fun observeActive(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE id = :id")
    fun observeById(id: String): Flow<ItemEntity?>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun getById(id: String): ItemEntity?

    @Query("SELECT * FROM items ORDER BY sortIndex ASC, name ASC")
    suspend fun getAll(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE sku = :sku LIMIT 1")
    suspend fun getBySku(sku: String): ItemEntity?

    @Query("SELECT DISTINCT category FROM items WHERE active = 1 ORDER BY category ASC")
    fun observeCategories(): Flow<List<String>>

    @Query(
        """
        SELECT category AS category, COUNT(*) AS itemCount
        FROM items GROUP BY category ORDER BY category ASC
        """
    )
    fun observeCategoryCounts(): Flow<List<CategoryCount>>

    @Query(
        """
        UPDATE items SET category = :to, updatedAt = :now, dirty = 1,
            pendingFields = CASE WHEN pendingFields = '' THEN 'category'
                                 ELSE pendingFields || ',category' END
        WHERE category = :from
        """
    )
    suspend fun renameCategory(from: String, to: String, now: Long)

    @Query("SELECT COUNT(*) FROM items")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM items WHERE costKnown = 0 AND active = 1")
    fun observeMissingCostCount(): Flow<Int>

    @Query("SELECT * FROM items WHERE active = 1 AND trackStock = 1 AND stockQty <= lowStockAt ORDER BY stockQty ASC, name ASC")
    suspend fun lowStockItems(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE dirty = 1 AND pendingFields != ''")
    suspend fun dirtyItems(): List<ItemEntity>

    @Upsert
    suspend fun upsert(item: ItemEntity)

    @Upsert
    suspend fun upsertAll(items: List<ItemEntity>)

    @Delete
    suspend fun delete(item: ItemEntity)

    @Query("DELETE FROM items")
    suspend fun deleteAll()

    @Query(
        """
        UPDATE items SET dirty = 0, pendingFields = '', pendingStockDelta = 0,
            stockAbsolutePending = 0 WHERE id IN (:ids)
        """
    )
    suspend fun clearDirty(ids: List<String>)

    @Query(
        """
        UPDATE items SET costCentavos = :cost, costKnown = 1, updatedAt = :now, dirty = 1,
            pendingFields = CASE WHEN pendingFields = '' THEN 'costCentavos,costKnown'
                                 ELSE pendingFields || ',costCentavos,costKnown' END
        WHERE id = :id
        """
    )
    suspend fun setCost(id: String, cost: Long, now: Long)

    /**
     * Never let stock go negative, and never touch stock for a service item — a cooking fee is
     * not something you can run out of.
     */
    @Query(
        """
        UPDATE items SET stockQty = MAX(0, stockQty - :qty), updatedAt = :now, dirty = 1,
            pendingFields = CASE WHEN pendingFields = '' THEN 'stockQty'
                                 ELSE pendingFields || ',stockQty' END,
            pendingStockDelta = CASE WHEN stockAbsolutePending = 1 THEN pendingStockDelta
                                     ELSE pendingStockDelta - MIN(stockQty, :qty) END
        WHERE id = :id AND trackStock = 1
        """
    )
    suspend fun decrementStock(id: String, qty: Int, now: Long)

    @Query(
        """
        UPDATE items SET stockQty = stockQty + :qty, updatedAt = :now, dirty = 1,
            pendingFields = CASE WHEN pendingFields = '' THEN 'stockQty'
                                 ELSE pendingFields || ',stockQty' END,
            pendingStockDelta = CASE WHEN stockAbsolutePending = 1 THEN pendingStockDelta
                                     ELSE pendingStockDelta + :qty END
        WHERE id = :id AND trackStock = 1
        """
    )
    suspend fun addStock(id: String, qty: Int, now: Long)

    @Query(
        """
        UPDATE items SET stockQty = :qty, updatedAt = :now, dirty = 1,
            pendingFields = CASE WHEN pendingFields = '' THEN 'stockQty'
                                 ELSE pendingFields || ',stockQty' END,
            pendingStockDelta = 0, stockAbsolutePending = 1
        WHERE id = :id
        """
    )
    suspend fun setStock(id: String, qty: Int, now: Long)
}

@Dao
interface SaleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSale(sale: SaleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSales(sales: List<SaleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLines(lines: List<SaleLineEntity>)

    /**
     * Next receipt number for this device only.
     *
     * Scoped by device on purpose: once another phone's sales sync in, a global MAX would make
     * this device's numbering jump forward and leave gaps in its own receipt book.
     */
    @Query("SELECT IFNULL(MAX(receiptNo), 0) + 1 FROM sales WHERE deviceCode = :deviceCode")
    suspend fun nextReceiptNo(deviceCode: String): Long

    @Query("SELECT * FROM sales WHERE voidedAt IS NULL AND returnedAt IS NULL ORDER BY soldAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<SaleEntity>>

    @Query("SELECT * FROM sales WHERE voidedAt IS NULL AND returnedAt IS NULL AND soldAt BETWEEN :from AND :to ORDER BY soldAt DESC")
    fun observeInRange(from: Long, to: Long): Flow<List<SaleEntity>>

    @Query("SELECT * FROM sales WHERE soldAt BETWEEN :from AND :to ORDER BY soldAt DESC")
    fun observeHistoryInRange(from: Long, to: Long): Flow<List<SaleEntity>>

    @Transaction
    @Query("SELECT * FROM sales WHERE id = :id")
    fun observeSaleWithLines(id: String): Flow<SaleWithLines?>

    @Transaction
    @Query("SELECT * FROM sales WHERE id = :id")
    suspend fun saleWithLines(id: String): SaleWithLines?

    @Query("SELECT * FROM sales WHERE id = :id")
    suspend fun getSaleById(id: String): SaleEntity?

    @Transaction
    @Query("SELECT * FROM sales WHERE voidedAt IS NULL AND returnedAt IS NULL AND soldAt BETWEEN :from AND :to ORDER BY soldAt DESC")
    suspend fun salesWithLinesInRange(from: Long, to: Long): List<SaleWithLines>

    @Transaction
    @Query("SELECT * FROM sales WHERE soldAt BETWEEN :from AND :to ORDER BY soldAt DESC")
    suspend fun historyWithLinesInRange(from: Long, to: Long): List<SaleWithLines>

    @Transaction
    @Query("SELECT * FROM sales WHERE source = 'TALLY' AND voidedAt IS NULL AND returnedAt IS NULL AND soldAt BETWEEN :from AND :to ORDER BY updatedAt DESC LIMIT 1")
    suspend fun activeTallyForDay(from: Long, to: Long): SaleWithLines?

    @Query(
        """
        SELECT COUNT(*) AS saleCount,
               IFNULL((SELECT SUM(l.qty) FROM sale_lines l
                       JOIN sales s2 ON s2.id = l.saleId
                       WHERE s2.voidedAt IS NULL AND s2.returnedAt IS NULL
                         AND s2.soldAt BETWEEN :from AND :to), 0) AS itemsSold,
               IFNULL(SUM(grossCentavos), 0)                                  AS gross,
               IFNULL(SUM(discountCentavos), 0)                               AS discount,
               IFNULL(SUM(netCentavos), 0)                                    AS net,
               IFNULL(SUM(costCentavos), 0)                                   AS cost,
               IFNULL(SUM(profitCentavos), 0)                                 AS profit,
               IFNULL((SELECT SUM(l.qty) FROM sale_lines l
                       JOIN sales s3 ON s3.id = l.saleId
                       WHERE s3.voidedAt IS NULL AND s3.returnedAt IS NULL
                         AND s3.soldAt BETWEEN :from AND :to
                         AND l.costKnown = 0), 0) AS unknownQty,
               IFNULL(SUM(unknownCostCentavos), 0)                            AS unknownNet
        FROM sales
        WHERE voidedAt IS NULL AND returnedAt IS NULL AND soldAt BETWEEN :from AND :to
        """
    )
    fun observeTotals(from: Long, to: Long): Flow<RangeTotals>

    @Query(
        """
        SELECT l.itemId    AS itemId,
               l.itemName  AS itemName,
               l.category  AS category,
               SUM(l.qty)                                                     AS qtySold,
               SUM(l.unitPriceCentavos * l.qty)                               AS gross,
               SUM(l.discountCentavos)                                        AS discount,
               SUM(l.unitPriceCentavos * l.qty - l.discountCentavos)          AS net,
               SUM(CASE WHEN l.costKnown = 1
                        THEN l.unitCostCentavos * l.qty ELSE 0 END)           AS cost,
               SUM(CASE WHEN l.costKnown = 1
                        THEN l.unitPriceCentavos * l.qty - l.discountCentavos
                             - l.unitCostCentavos * l.qty ELSE 0 END)         AS profit,
               SUM(CASE WHEN l.costKnown = 0 THEN l.qty ELSE 0 END)           AS unknownQty,
               SUM(CASE WHEN l.costKnown = 0
                        THEN l.unitPriceCentavos * l.qty - l.discountCentavos
                        ELSE 0 END)                                           AS unknownNet
        FROM sale_lines l
        JOIN sales s ON s.id = l.saleId
        WHERE s.voidedAt IS NULL AND s.returnedAt IS NULL AND s.soldAt BETWEEN :from AND :to
        GROUP BY l.itemId, l.itemName, l.category
        ORDER BY qtySold DESC, net DESC
        """
    )
    fun observeItemBreakdown(from: Long, to: Long): Flow<List<ItemSalesRow>>

    @Query(
        """
        SELECT CASE WHEN l.category = '' THEN 'Uncategorised' ELSE l.category END AS category,
               SUM(l.qty)                                                     AS qtySold,
               SUM(l.unitPriceCentavos * l.qty - l.discountCentavos)          AS net,
               SUM(CASE WHEN l.costKnown = 1
                        THEN l.unitCostCentavos * l.qty ELSE 0 END)           AS cost,
               SUM(CASE WHEN l.costKnown = 1
                        THEN l.unitPriceCentavos * l.qty - l.discountCentavos
                             - l.unitCostCentavos * l.qty ELSE 0 END)         AS profit,
               SUM(CASE WHEN l.costKnown = 0
                        THEN l.unitPriceCentavos * l.qty - l.discountCentavos
                        ELSE 0 END)                                           AS unknownNet
        FROM sale_lines l
        JOIN sales s ON s.id = l.saleId
        WHERE s.voidedAt IS NULL AND s.returnedAt IS NULL AND s.soldAt BETWEEN :from AND :to
        GROUP BY category
        ORDER BY net DESC
        """
    )
    fun observeCategoryBreakdown(from: Long, to: Long): Flow<List<CategorySalesRow>>

    @Query(
        """
        SELECT l.tier AS tier,
               SUM(l.qty)                                                     AS qtySold,
               SUM(l.unitPriceCentavos * l.qty - l.discountCentavos)          AS net,
               SUM(CASE WHEN l.costKnown = 1
                        THEN l.unitPriceCentavos * l.qty - l.discountCentavos
                             - l.unitCostCentavos * l.qty ELSE 0 END)         AS profit
        FROM sale_lines l
        JOIN sales s ON s.id = l.saleId
        WHERE s.voidedAt IS NULL AND s.returnedAt IS NULL AND s.soldAt BETWEEN :from AND :to
        GROUP BY l.tier
        """
    )
    fun observeTierBreakdown(from: Long, to: Long): Flow<List<TierSalesRow>>

    @Query(
        """
        SELECT cashierId AS cashierId,
               CASE WHEN cashierName = '' THEN 'Unknown employee' ELSE cashierName END AS cashierName,
               COUNT(*) AS saleCount,
               IFNULL(SUM((SELECT SUM(l.qty) FROM sale_lines l WHERE l.saleId = sales.id)), 0) AS itemsSold,
               IFNULL(SUM(netCentavos), 0) AS net,
               IFNULL(SUM(costCentavos), 0) AS cost,
               IFNULL(SUM(profitCentavos), 0) AS profit,
               IFNULL(SUM(unknownCostCentavos), 0) AS unknownNet
        FROM sales
        WHERE voidedAt IS NULL AND returnedAt IS NULL AND soldAt BETWEEN :from AND :to
        GROUP BY cashierId, cashierName
        ORDER BY net DESC
        """
    )
    fun observeEmployeeBreakdown(from: Long, to: Long): Flow<List<EmployeeSalesRow>>

    @Query(
        """
        SELECT CASE WHEN paymentMethod = '' THEN 'Other' ELSE paymentMethod END AS paymentMethod,
               COUNT(*) AS saleCount,
               IFNULL(SUM((SELECT SUM(l.qty) FROM sale_lines l WHERE l.saleId = sales.id)), 0) AS itemsSold,
               IFNULL(SUM(netCentavos), 0) AS net,
               IFNULL(SUM(profitCentavos), 0) AS profit
        FROM sales
        WHERE voidedAt IS NULL AND returnedAt IS NULL AND soldAt BETWEEN :from AND :to
        GROUP BY paymentMethod
        ORDER BY net DESC
        """
    )
    fun observePaymentBreakdown(from: Long, to: Long): Flow<List<PaymentSalesRow>>

    @Query(
        """
        SELECT CASE WHEN orderType = '' THEN 'UNSPECIFIED' ELSE orderType END AS orderType,
               COUNT(*) AS saleCount,
               IFNULL(SUM((SELECT SUM(l.qty) FROM sale_lines l WHERE l.saleId = sales.id)), 0) AS itemsSold,
               IFNULL(SUM(netCentavos), 0) AS net,
               IFNULL(SUM(profitCentavos), 0) AS profit
        FROM sales
        WHERE voidedAt IS NULL AND returnedAt IS NULL AND soldAt BETWEEN :from AND :to
        GROUP BY orderType
        ORDER BY net DESC
        """
    )
    fun observeOrderTypeBreakdown(from: Long, to: Long): Flow<List<OrderTypeSalesRow>>

    @Query(
        """
        SELECT strftime('%Y-%m-%d', soldAt / 1000, 'unixepoch', 'localtime') AS day,
               IFNULL(SUM(netCentavos), 0)    AS net,
               IFNULL(SUM(profitCentavos), 0) AS profit
        FROM sales
        WHERE voidedAt IS NULL AND returnedAt IS NULL AND soldAt BETWEEN :from AND :to
        GROUP BY day
        ORDER BY day ASC
        """
    )
    fun observeDailySeries(from: Long, to: Long): Flow<List<DailyPoint>>

    @Query("SELECT * FROM sales WHERE dirty = 1")
    suspend fun dirtySales(): List<SaleEntity>

    @Query("SELECT * FROM sale_lines WHERE saleId = :saleId")
    suspend fun linesForSale(saleId: String): List<SaleLineEntity>

    @Query("UPDATE sales SET dirty = 0 WHERE id IN (:ids)")
    suspend fun clearDirty(ids: List<String>)

    @Query("UPDATE sales SET voidedAt = :now, updatedAt = :now, dirty = 1 WHERE id = :id AND voidedAt IS NULL AND returnedAt IS NULL")
    suspend fun markVoided(id: String, now: Long)

    @Query("UPDATE sales SET returnedAt = :now, returnReason = :reason, updatedAt = :now, dirty = 1 WHERE id = :id AND voidedAt IS NULL AND returnedAt IS NULL")
    suspend fun markReturned(id: String, reason: String, now: Long)

    @Query("DELETE FROM sales WHERE id = :id")
    suspend fun deleteSale(id: String)

    /** Every sale of one kind on a day, whatever its status — the set a re-import has to replace. */
    @Query("SELECT * FROM sales WHERE source = :source AND soldAt BETWEEN :from AND :to")
    suspend fun salesOfSourceInRange(source: String, from: Long, to: Long): List<SaleEntity>

    @Query("DELETE FROM sales WHERE id IN (:ids)")
    suspend fun deleteSales(ids: List<String>)

    @Query("SELECT COUNT(*) FROM sales")
    suspend fun countSales(): Int

    @Query("SELECT id FROM sales")
    suspend fun allSaleIds(): List<String>

    @Query("DELETE FROM sales")
    suspend fun deleteAllSales()
}

@Dao
interface UserDao {

    @Query("SELECT * FROM users ORDER BY role ASC, displayName ASC")
    fun observeAll(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE uid = :uid")
    suspend fun getById(uid: String): UserEntity?

    @Query("SELECT * FROM users WHERE email = :email LIMIT 1")
    suspend fun getByEmail(email: String): UserEntity?

    @Query("SELECT COUNT(*) FROM users")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(user: UserEntity)

    @Upsert
    suspend fun upsertAll(users: List<UserEntity>)

    @Query("SELECT * FROM users WHERE dirty = 1")
    suspend fun dirtyUsers(): List<UserEntity>

    @Query("UPDATE users SET dirty = 0 WHERE uid IN (:ids)")
    suspend fun clearDirty(ids: List<String>)

    @Query("DELETE FROM users WHERE uid = :uid")
    suspend fun delete(uid: String)
}

@Dao
interface NoticeDao {

    @Query("SELECT * FROM notices WHERE deletedAt IS NULL ORDER BY sentAt DESC LIMIT :limit")
    fun observeAll(limit: Int = 200): Flow<List<NoticeEntity>>

    @Query("SELECT COUNT(*) FROM notices WHERE readAt IS NULL AND deletedAt IS NULL")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT * FROM notices WHERE id = :id")
    suspend fun getById(id: String): NoticeEntity?

    @Upsert
    suspend fun upsert(notice: NoticeEntity)

    @Upsert
    suspend fun upsertAll(notices: List<NoticeEntity>)

    @Query("UPDATE notices SET readAt = :now WHERE readAt IS NULL")
    suspend fun markAllRead(now: Long)

    @Query("UPDATE notices SET readAt = :now WHERE id = :id AND readAt IS NULL")
    suspend fun markRead(id: String, now: Long)

    @Query("SELECT * FROM notices WHERE dirty = 1")
    suspend fun dirtyNotices(): List<NoticeEntity>

    @Query("UPDATE notices SET dirty = 0 WHERE id IN (:ids)")
    suspend fun clearDirty(ids: List<String>)

    @Query("SELECT MAX(sentAt) FROM notices")
    suspend fun latestSentAt(): Long?

    @Query("SELECT MAX(updatedAt) FROM notices")
    suspend fun latestUpdatedAt(): Long?

    @Query("UPDATE notices SET deletedAt = :now, updatedAt = :now, dirty = 1 WHERE id = :id")
    suspend fun markDeleted(id: String, now: Long)

    @Query("DELETE FROM notices WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TicketDao {
    @Transaction
    @Query("SELECT * FROM tickets ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<TicketWithLines>>

    @Transaction
    @Query("SELECT * FROM tickets WHERE id = :id")
    suspend fun getWithLines(id: String): TicketWithLines?

    @Upsert
    suspend fun upsert(ticket: TicketEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLines(lines: List<TicketLineEntity>)

    @Query("DELETE FROM ticket_lines WHERE ticketId = :ticketId")
    suspend fun deleteLines(ticketId: String)

    @Query("DELETE FROM tickets WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface AuditLogDao {
    @Query("SELECT * FROM audit_logs WHERE businessDateEpochDay = :epochDay ORDER BY occurredAt DESC")
    fun observeForDate(epochDay: Long): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_logs ORDER BY occurredAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_logs WHERE dirty = 1")
    suspend fun dirtyLogs(): List<AuditLogEntity>

    @Upsert
    suspend fun upsert(log: AuditLogEntity)

    @Upsert
    suspend fun upsertAll(logs: List<AuditLogEntity>)

    @Query("UPDATE audit_logs SET dirty = 0 WHERE id IN (:ids)")
    suspend fun clearDirty(ids: List<String>)
}
