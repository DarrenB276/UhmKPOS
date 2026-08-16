package com.uhmk.pos.core.sync

import android.content.Context
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import androidx.room.withTransaction
import com.uhmk.pos.core.db.AppDatabase
import com.uhmk.pos.core.db.AuditLogEntity
import com.uhmk.pos.core.db.ItemEntity
import com.uhmk.pos.core.db.NoticeEntity
import com.uhmk.pos.core.db.SaleEntity
import com.uhmk.pos.core.db.SaleLineEntity
import com.uhmk.pos.core.db.UserEntity
import com.uhmk.pos.core.model.PriceTier
import com.uhmk.pos.core.model.UserRole
import com.uhmk.pos.core.prefs.SessionStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.util.UUID

/**
 * Pushes locally-changed rows to Firestore and pulls remote changes back into Room.
 *
 * Room keeps the till working offline, while Firestore is the shared catalogue of record. Item
 * writes are versioned and field-level: a stale device can upload only fields a person actually
 * changed, and an old blank cost can never erase a known supplier cost.
 */
class SyncManager(
    private val context: Context,
    private val db: AppDatabase,
    private val syncStore: SyncStore,
    private val sessionStore: SessionStore,
) {

    val isCloudEnabled: Boolean get() = FirebaseGate.isConfigured(context)

    private val firestore: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance().apply {
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                // Local persistence means queued writes survive a restart and reach the server later.
                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                .build()
        }
    }

    data class SyncReport(
        val itemsPushed: Int = 0,
        val salesPushed: Int = 0,
        val usersPushed: Int = 0,
        val noticesPushed: Int = 0,
        val auditLogsPushed: Int = 0,
        val itemsPulled: Int = 0,
        val salesPulled: Int = 0,
        val usersPulled: Int = 0,
        val noticesPulled: Int = 0,
        val auditLogsPulled: Int = 0,
        val itemConflictsResolved: Int = 0,
        val knownCostsProtected: Int = 0,
    )

    suspend fun syncAll(): Result<SyncReport> {
        if (!isCloudEnabled) return Result.failure(IllegalStateException("Firebase is not configured"))

        return runCatching {
            // Remember when the sync began, not when it ended. A write arriving during the sync
            // will then still be newer than the cursor and cannot fall through the gap.
            val syncStartedAt = System.currentTimeMillis()
            val pulled = pull()
            val pushed = push()
            syncStore.setLastSyncAt(syncStartedAt)
            pushed.copy(
                itemsPulled = pulled.itemsPulled,
                salesPulled = pulled.salesPulled,
                usersPulled = pulled.usersPulled,
                noticesPulled = pulled.noticesPulled,
                auditLogsPulled = pulled.auditLogsPulled,
                itemConflictsResolved = pushed.itemConflictsResolved +
                    pulled.itemConflictsResolved,
                knownCostsProtected = pushed.knownCostsProtected + pulled.knownCostsProtected,
            )
        }.onFailure { Log.w(TAG, "Sync failed: ${it.message}") }
    }

    // ---------------- push ----------------

    private suspend fun push(): SyncReport {
        val itemDao = db.itemDao()
        val saleDao = db.saleDao()
        val userDao = db.userDao()
        val noticeDao = db.noticeDao()
        val auditDao = db.auditLogDao()
        val session = sessionStore.session.first()
        val isAdmin = session.isAdmin

        // Staff sales are authoritative; catalogue/user/notice edits are owner-only. Skipping
        // staff catalogue rows also prevents the built-in seed from overwriting live prices.
        val items = if (isAdmin) itemDao.dirtyItems() else emptyList()
        val itemOutcomes = items.map {
            pushItem(it, session.uid, session.displayName.ifBlank { session.email })
        }
        if (itemOutcomes.isNotEmpty()) itemDao.upsertAll(itemOutcomes.map(ItemPushOutcome::item))

        val sales = saleDao.dirtySales()
        if (sales.isNotEmpty()) {
            val batch = firestore.batch()
            sales.forEach { sale ->
                val lines = saleDao.linesForSale(sale.id).map { line ->
                    mapOf(
                        "id" to line.id,
                        "itemId" to line.itemId,
                        "itemName" to line.itemName,
                        "category" to line.category,
                        "tier" to line.tier.name,
                        "unitPriceCentavos" to line.unitPriceCentavos,
                        "unitCostCentavos" to line.unitCostCentavos,
                        "costKnown" to line.costKnown,
                        "qty" to line.qty,
                        "discountCentavos" to line.discountCentavos,
                    )
                }
                batch.set(
                    firestore.collection(SALES).document(sale.id),
                    mapOf(
                        "id" to sale.id,
                        "soldAt" to sale.soldAt,
                        "cashierId" to sale.cashierId,
                        "cashierName" to sale.cashierName,
                        "receiptNo" to sale.receiptNo,
                        "deviceCode" to sale.deviceCode,
                        "grossCentavos" to sale.grossCentavos,
                        "discountCentavos" to sale.discountCentavos,
                        "netCentavos" to sale.netCentavos,
                        "costCentavos" to sale.costCentavos,
                        "profitCentavos" to sale.profitCentavos,
                        "unknownCostCentavos" to sale.unknownCostCentavos,
                        "paymentMethod" to sale.paymentMethod,
                        "tenderedCentavos" to sale.tenderedCentavos,
                        "orderType" to sale.orderType,
                        "orderLabel" to sale.orderLabel,
                        "note" to sale.note,
                        "updatedAt" to sale.updatedAt,
                        "voidedAt" to sale.voidedAt,
                        "returnedAt" to sale.returnedAt,
                        "returnReason" to sale.returnReason,
                        "source" to sale.source,
                        "lines" to lines,
                    ),
                )
            }
            batch.commit().await()
            saleDao.clearDirty(sales.map { it.id })
        }

        val users = if (isAdmin) userDao.dirtyUsers() else emptyList()
        if (users.isNotEmpty()) {
            val batch = firestore.batch()
            users.forEach { batch.set(firestore.collection(USERS).document(it.uid), it.toMap()) }
            batch.commit().await()
            userDao.clearDirty(users.map { it.uid })
        }

        val notices = if (isAdmin) noticeDao.dirtyNotices() else emptyList()
        if (notices.isNotEmpty()) {
            val batch = firestore.batch()
            notices.forEach { batch.set(firestore.collection(NOTICES).document(it.id), it.toMap()) }
            batch.commit().await()
            noticeDao.clearDirty(notices.map { it.id })
        }

        val auditLogs = if (isAdmin) auditDao.dirtyLogs() else emptyList()
        if (auditLogs.isNotEmpty()) {
            val batch = firestore.batch()
            auditLogs.forEach { log ->
                batch.set(firestore.collection(AUDIT_LOGS).document(log.id), log.toMap())
            }
            batch.commit().await()
            auditDao.clearDirty(auditLogs.map { it.id })
        }

        return SyncReport(
            itemsPushed = itemOutcomes.count { it.pushed },
            salesPushed = sales.size,
            usersPushed = users.size,
            noticesPushed = notices.size,
            auditLogsPushed = auditLogs.size,
            itemConflictsResolved = itemOutcomes.count { it.hadVersionConflict },
            knownCostsProtected = itemOutcomes.count { it.protectedKnownCost },
        )
    }

    // ---------------- pull ----------------

    private suspend fun pull(): SyncReport {
        val since = syncStore.lastSyncAt()
        val isAdmin = sessionStore.session.first().isAdmin

        // First sync reads the complete catalogue, then server timestamps make later pulls
        // incremental. Source.SERVER is deliberate: a cached empty result must never make a fresh
        // device publish its seed over an existing store catalogue.
        val catalogueCursor = syncStore.lastCatalogueServerAt()
        val catalogueQuery = if (catalogueCursor <= 0) {
            firestore.collection(ITEMS)
        } else {
            firestore.collection(ITEMS).whereGreaterThan(
                "serverUpdatedAt",
                Timestamp((catalogueCursor / 1_000), ((catalogueCursor % 1_000) * 1_000_000).toInt()),
            )
        }
        val catalogueSnapshot = catalogueQuery.get(Source.SERVER).await()
        val remoteItems = catalogueSnapshot.documents.mapNotNull { it.data?.toItem() }
        catalogueSnapshot.documents.mapNotNull { it.getTimestamp("serverUpdatedAt") }
            .maxOrNull()
            ?.let { newest ->
                // Re-read the final millisecond next time. That tiny overlap closes the gap when
                // two server writes happen within the same millisecond.
                syncStore.setLastCatalogueServerAt(newest.toDate().time - 1)
            }

        val itemDao = db.itemDao()
        var itemConflicts = 0
        var protectedCosts = 0
        val freshItems = remoteItems.mapNotNull { remote ->
            val local = itemDao.getById(remote.id)
            val merged = when {
                local == null -> remote
                !isAdmin -> remote.copy(imagePath = local.imagePath)
                else -> ItemSyncPolicy.mergeRemote(remote, local).also { result ->
                    if (result.hadVersionConflict) itemConflicts++
                    if (result.protectedKnownCost) protectedCosts++
                }.item
            }
            merged.takeIf { it != local }
        }
        if (freshItems.isNotEmpty()) itemDao.upsertAll(freshItems)

        // A genuinely empty Firebase project may be bootstrapped from the bundled catalogue, but
        // only after a successful server read proves that no cloud item exists.
        if (catalogueCursor <= 0 && remoteItems.isEmpty() && isAdmin) {
            val now = System.currentTimeMillis()
            val bootstrap = itemDao.getAll().map { local ->
                ItemSyncPolicy.prepareLocalChange(null, local, now)
            }
            if (bootstrap.isNotEmpty()) itemDao.upsertAll(bootstrap)
        }

        // Sales are immutable except for a void tombstone. Pulling them makes staff receipts and
        // day totals visible on the admin device; updatedAt also catches tallies stamped in the past.
        val remoteSales = if (isAdmin) {
            firestore.collection(SALES)
                .whereGreaterThan("updatedAt", since)
                .get().await()
                .documents.mapNotNull { it.data?.toRemoteSale() }
        } else {
            emptyList()
        }
        val saleDao = db.saleDao()
        val freshSales = remoteSales.mapNotNull { remote ->
            val local = saleDao.getSaleById(remote.sale.id)
            if (local == null || remote.sale.updatedAt > local.updatedAt) remote to local else null
        }
        if (freshSales.isNotEmpty()) {
            db.withTransaction {
                freshSales.forEach { (remote, local) ->
                    // On first sync, the remote item snapshot already includes historical stock.
                    // Later, newly-arrived staff sales need applying because staff cannot rewrite
                    // the owner-controlled item document directly.
                    if (since > 0) {
                        when {
                            local == null && remote.sale.voidedAt == null &&
                                remote.sale.returnedAt == null ->
                                remote.lines.forEach {
                                    itemDao.decrementStock(it.itemId, it.qty, remote.sale.updatedAt)
                                }
                            local != null && local.voidedAt == null && local.returnedAt == null &&
                                (remote.sale.voidedAt != null || remote.sale.returnedAt != null) ->
                                remote.lines.forEach {
                                    itemDao.addStock(it.itemId, it.qty, remote.sale.updatedAt)
                                }
                        }
                    }
                    saleDao.insertSale(remote.sale.copy(dirty = false))
                    if (remote.lines.isNotEmpty()) saleDao.insertLines(remote.lines)
                }
            }
        }

        // The account list is intentionally small. Pull it in full so an admin can always target
        // a notice even when a manually-created role document predates this device's sync cursor.
        val remoteUsers = firestore.collection(USERS)
            .get().await()
            .documents.mapNotNull { it.data?.toUser() }
        if (remoteUsers.isNotEmpty()) {
            db.userDao().upsertAll(remoteUsers.map { it.copy(dirty = false) })
        }

        val noticeDao = db.noticeDao()
        val latestNotice = noticeDao.latestUpdatedAt() ?: 0L
        val session = sessionStore.session.first()
        val noticeQuery = if (session.isAdmin) {
            firestore.collection(NOTICES)
                .whereGreaterThan("updatedAt", latestNotice)
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .limit(50)
        } else {
            // Query only broadcasts plus this account's private messages so Firestore rules can
            // enforce that a targeted notice is actually private.
            firestore.collection(NOTICES)
                .whereIn("targetUid", listOf("", session.uid))
                .limit(100)
        }
        val remoteNotices = noticeQuery.get().await()
            .documents.mapNotNull { it.data?.toNotice() }
            .filter { it.updatedAt > latestNotice }
        if (remoteNotices.isNotEmpty()) {
            noticeDao.upsertAll(remoteNotices.map { it.copy(dirty = false) })
        }


        val remoteAuditLogs = if (isAdmin) {
            firestore.collection(AUDIT_LOGS)
                .whereGreaterThan("occurredAt", since)
                .get().await()
                .documents.mapNotNull { it.data?.toAuditLog() }
        } else emptyList()
        if (remoteAuditLogs.isNotEmpty()) {
            db.auditLogDao().upsertAll(remoteAuditLogs.map { it.copy(dirty = false) })
        }

        return SyncReport(
            itemsPulled = freshItems.size,
            salesPulled = freshSales.size,
            usersPulled = remoteUsers.size,
            noticesPulled = remoteNotices.size,
            auditLogsPulled = remoteAuditLogs.size,
            itemConflictsResolved = itemConflicts,
            knownCostsProtected = protectedCosts,
        )
    }

    private data class ItemPushOutcome(
        val item: ItemEntity,
        val pushed: Boolean,
        val hadVersionConflict: Boolean,
        val protectedKnownCost: Boolean,
    )

    /**
     * A Firestore transaction compares the local baseline with the current cloud revision before
     * applying a patch. This remains safe even when two admins press Sync at the same moment.
     */
    private suspend fun pushItem(
        local: ItemEntity,
        actorId: String,
        actorName: String,
    ): ItemPushOutcome {
        val itemRef = firestore.collection(ITEMS).document(local.id)
        val auditRef = firestore.collection(AUDIT_LOGS).document(UUID.randomUUID().toString())
        val occurredAt = System.currentTimeMillis()
        val businessDate = LocalDate.now().toEpochDay()

        return firestore.runTransaction { transaction ->
            val snapshot = transaction.get(itemRef)
            val remote = snapshot.data?.toItem()
            val merge = if (remote == null) {
                ItemSyncPolicy.MergeResult(
                    item = local,
                    hadVersionConflict = false,
                    protectedKnownCost = false,
                )
            } else {
                ItemSyncPolicy.mergeRemote(remote, local)
            }
            val changedFields = if (remote == null) {
                ItemSyncPolicy.ALL_FIELDS
            } else {
                ItemSyncPolicy.decode(merge.item.pendingFields)
            }

            if (changedFields.isEmpty()) {
                return@runTransaction ItemPushOutcome(
                    item = (remote ?: merge.item).copy(
                        imagePath = local.imagePath,
                        pendingFields = "",
                        pendingStockDelta = 0,
                        stockAbsolutePending = false,
                        dirty = false,
                    ),
                    pushed = false,
                    hadVersionConflict = merge.hadVersionConflict,
                    protectedKnownCost = merge.protectedKnownCost,
                )
            }

            val nextVersion = (remote?.cloudVersion ?: 0) + 1
            val updated = merge.item.copy(
                updatedAt = occurredAt,
                cloudVersion = nextVersion,
                pendingFields = "",
                pendingStockDelta = 0,
                stockAbsolutePending = false,
                dirty = false,
            )
            val patch = ItemSyncPolicy.cloudValues(updated, changedFields).toMutableMap().apply {
                put("id", updated.id)
                put("revision", nextVersion)
                put("updatedAt", occurredAt)
                put("serverUpdatedAt", FieldValue.serverTimestamp())
                put("lastChangedFields", changedFields.toList())
                put("updatedByUid", actorId)
                put("updatedByName", actorName)
            }
            transaction.set(itemRef, patch, SetOptions.merge())

            // New-project bootstrap creates many starter rows; audit only real catalogue edits.
            if (remote != null) {
                transaction.set(
                    auditRef,
                    mapOf(
                        "id" to auditRef.id,
                        "action" to "ITEM_UPDATE",
                        "entityId" to local.id,
                        "businessDateEpochDay" to businessDate,
                        "actorId" to actorId,
                        "actorName" to actorName,
                        "occurredAt" to occurredAt,
                        "beforeSummary" to itemSummary(remote, changedFields),
                        "afterSummary" to itemSummary(updated, changedFields),
                        "changedFields" to changedFields.toList(),
                        "fromRevision" to remote.cloudVersion,
                        "toRevision" to nextVersion,
                    ),
                )
            }

            ItemPushOutcome(
                item = updated.copy(imagePath = local.imagePath),
                pushed = true,
                hadVersionConflict = merge.hadVersionConflict,
                protectedKnownCost = merge.protectedKnownCost,
            )
        }.await()
    }

    private fun itemSummary(item: ItemEntity, fields: Set<String>): String = fields.joinToString(", ") {
        val value = when (it) {
            ItemSyncPolicy.NAME -> item.name
            ItemSyncPolicy.CATEGORY -> item.category
            ItemSyncPolicy.SKU -> item.sku
            ItemSyncPolicy.TRACK_STOCK -> item.trackStock
            ItemSyncPolicy.COST -> if (item.costKnown) item.costCentavos else "not set"
            ItemSyncPolicy.COST_KNOWN -> item.costKnown
            ItemSyncPolicy.STUDENT_PRICE -> item.studentCentavos
            ItemSyncPolicy.REGULAR_PRICE -> item.regularCentavos
            ItemSyncPolicy.BOX_COST -> item.boxCostCentavos
            ItemSyncPolicy.UNITS_PER_BOX -> item.unitsPerBox
            ItemSyncPolicy.STOCK -> item.stockQty
            ItemSyncPolicy.LOW_STOCK_AT -> item.lowStockAt
            ItemSyncPolicy.ACTIVE -> item.active
            ItemSyncPolicy.SORT_INDEX -> item.sortIndex
            else -> ""
        }
        "$it=$value"
    }

    // ---------------- notices ----------------

    suspend fun pushNotice(notice: NoticeEntity): Result<Unit> {
        if (!isCloudEnabled) return Result.failure(IllegalStateException("Firebase is not configured"))
        return runCatching {
            firestore.collection(NOTICES).document(notice.id).set(notice.toMap()).await()
            db.noticeDao().clearDirty(listOf(notice.id))
        }
    }

    /**
     * Live stream of notices newer than [since].
     *
     * This is what makes admin→staff messaging work on the free Spark plan: real push would need
     * Cloud Functions, which is a Blaze-only feature, so each device listens for itself.
     */
    fun observeRemoteNotices(since: Long): Flow<List<NoticeEntity>> = callbackFlow {
        if (!isCloudEnabled) {
            close()
            return@callbackFlow
        }

        val session = sessionStore.session.first()
        val query = if (session.isAdmin) {
            firestore.collection(NOTICES)
                .whereGreaterThan("updatedAt", since)
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .limit(30)
        } else {
            firestore.collection(NOTICES)
                .whereIn("targetUid", listOf("", session.uid))
                .limit(100)
        }
        val registration: ListenerRegistration = query
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Notice listener error: ${error.message}")
                    return@addSnapshotListener
                }
                val notices = snapshot?.documents?.mapNotNull { it.data?.toNotice() }
                    .orEmpty().filter { it.updatedAt > since }
                trySend(notices)
            }

        awaitClose { registration.remove() }
    }

    /** Live sale stream used by admin devices for optional receipt notifications. */
    fun observeRemoteSales(since: Long): Flow<List<SaleEntity>> = callbackFlow {
        if (!isCloudEnabled) {
            close()
            return@callbackFlow
        }
        val registration = firestore.collection(SALES)
            .whereGreaterThan("updatedAt", since)
            .orderBy("updatedAt", Query.Direction.ASCENDING)
            .limit(50)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Sale-alert listener error: ${error.message}")
                    return@addSnapshotListener
                }
                trySend(
                    snapshot?.documents?.mapNotNull { it.data?.toRemoteSale()?.sale }.orEmpty()
                )
            }
        awaitClose { registration.remove() }
    }

    /**
     * Clears the cloud copy of every sale.
     *
     * Without this a local reset is undone by the next pull, which would quietly resurrect the
     * very test sales the user just cleared. Batched because Firestore caps a write batch at 500.
     */
    suspend fun deleteAllRemoteSales(): Result<Int> {
        if (!isCloudEnabled) return Result.success(0)
        return runCatching {
            var removed = 0
            val docs = firestore.collection(SALES).get().await().documents
            docs.chunked(400).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { batch.delete(it.reference) }
                batch.commit().await()
                removed += chunk.size
            }
            removed
        }
    }

    suspend fun registerFcmToken(uid: String, token: String) {
        if (!isCloudEnabled || uid.isBlank()) return
        runCatching {
            firestore.collection(USERS).document(uid)
                .set(mapOf("fcmToken" to token), com.google.firebase.firestore.SetOptions.merge())
                .await()
        }
    }

    private companion object {
        const val TAG = "SyncManager"
        const val ITEMS = "items"
        const val SALES = "sales"
        const val USERS = "users"
        const val NOTICES = "notices"
        const val AUDIT_LOGS = "auditLogs"
    }
}

// ---------------- mapping ----------------
// Plain maps rather than annotated data classes: no reflection, so nothing to keep in ProGuard
// and no surprise when a field is renamed.

private fun ItemEntity.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "name" to name,
    "category" to category,
    "sku" to sku,
    "trackStock" to trackStock,
    "costCentavos" to costCentavos,
    "costKnown" to costKnown,
    "studentCentavos" to studentCentavos,
    "regularCentavos" to regularCentavos,
    "boxCostCentavos" to boxCostCentavos,
    "unitsPerBox" to unitsPerBox,
    "stockQty" to stockQty,
    "lowStockAt" to lowStockAt,
    "active" to active,
    "sortIndex" to sortIndex,
    "updatedAt" to updatedAt,
    "revision" to cloudVersion,
)

private fun Map<String, Any?>.toItem(): ItemEntity? {
    val id = this["id"] as? String ?: return null
    return ItemEntity(
        id = id,
        name = this["name"] as? String ?: return null,
        category = this["category"] as? String ?: "General",
        sku = this["sku"] as? String ?: "",
        trackStock = this["trackStock"] as? Boolean ?: true,
        costCentavos = long("costCentavos"),
        costKnown = this["costKnown"] as? Boolean
            ?: (long("costCentavos") > 0 || id == "itm-cooking-fee"),
        studentCentavos = long("studentCentavos"),
        regularCentavos = long("regularCentavos"),
        boxCostCentavos = long("boxCostCentavos"),
        unitsPerBox = int("unitsPerBox", 1),
        stockQty = int("stockQty", 0),
        lowStockAt = int("lowStockAt", 5),
        // Photos stay on the device that took them; Cloud Storage is not part of the free tier plan.
        imagePath = null,
        active = this["active"] as? Boolean ?: true,
        sortIndex = int("sortIndex", 0),
        updatedAt = long("updatedAt"),
        cloudVersion = long("revision"),
        pendingFields = "",
        dirty = false,
    )
}

private data class RemoteSale(val sale: SaleEntity, val lines: List<SaleLineEntity>)

private fun Map<String, Any?>.toRemoteSale(): RemoteSale? {
    val id = this["id"] as? String ?: return null
    val lineMaps = (this["lines"] as? List<*>)
        .orEmpty()
        .mapNotNull { it as? Map<String, Any?> }
    val lines = lineMaps.mapNotNull { line ->
        val lineId = line["id"] as? String ?: return@mapNotNull null
        SaleLineEntity(
            id = lineId,
            saleId = id,
            itemId = line["itemId"] as? String ?: "",
            itemName = line["itemName"] as? String ?: "Item",
            category = line["category"] as? String ?: "",
            tier = PriceTier.from(line["tier"] as? String),
            unitPriceCentavos = line.long("unitPriceCentavos"),
            unitCostCentavos = line.long("unitCostCentavos"),
            costKnown = line["costKnown"] as? Boolean ?: true,
            qty = line.int("qty", 1),
            discountCentavos = line.long("discountCentavos"),
        )
    }
    val soldAt = long("soldAt")
    return RemoteSale(
        sale = SaleEntity(
            id = id,
            soldAt = soldAt,
            cashierId = this["cashierId"] as? String ?: "",
            cashierName = this["cashierName"] as? String ?: "Staff",
            receiptNo = long("receiptNo"),
            deviceCode = this["deviceCode"] as? String ?: "",
            grossCentavos = long("grossCentavos"),
            discountCentavos = long("discountCentavos"),
            netCentavos = long("netCentavos"),
            costCentavos = long("costCentavos"),
            profitCentavos = long("profitCentavos"),
            unknownCostCentavos = long("unknownCostCentavos"),
            paymentMethod = this["paymentMethod"] as? String ?: "Cash",
            tenderedCentavos = long("tenderedCentavos"),
            orderType = this["orderType"] as? String ?: "UNSPECIFIED",
            orderLabel = this["orderLabel"] as? String ?: "",
            note = this["note"] as? String ?: "",
            updatedAt = long("updatedAt", soldAt),
            voidedAt = (this["voidedAt"] as? Number)?.toLong(),
            returnedAt = (this["returnedAt"] as? Number)?.toLong(),
            returnReason = this["returnReason"] as? String ?: "",
            source = this["source"] as? String ?: "POS",
            dirty = false,
        ),
        lines = lines,
    )
}

private fun UserEntity.toMap(): Map<String, Any?> = mapOf(
    "uid" to uid,
    "email" to email,
    "displayName" to displayName,
    "role" to role.name,
    "active" to active,
    "fcmToken" to fcmToken,
    "createdAt" to createdAt,
    "updatedAt" to updatedAt,
)

private fun Map<String, Any?>.toUser(): UserEntity? {
    val uid = this["uid"] as? String ?: return null
    return UserEntity(
        uid = uid,
        email = this["email"] as? String ?: "",
        displayName = this["displayName"] as? String ?: "",
        role = UserRole.from(this["role"] as? String),
        active = this["active"] as? Boolean ?: true,
        fcmToken = this["fcmToken"] as? String,
        createdAt = long("createdAt"),
        updatedAt = long("updatedAt"),
        dirty = false,
    )
}

private fun NoticeEntity.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "title" to title,
    "body" to body,
    "senderName" to senderName,
    "targetUid" to targetUid,
    "targetName" to targetName,
    "sentAt" to sentAt,
    "updatedAt" to updatedAt,
    "deletedAt" to deletedAt,
)

private fun Map<String, Any?>.toNotice(): NoticeEntity? {
    val id = this["id"] as? String ?: return null
    return NoticeEntity(
        id = id,
        title = this["title"] as? String ?: "",
        body = this["body"] as? String ?: "",
        senderName = this["senderName"] as? String ?: "Admin",
        targetUid = this["targetUid"] as? String ?: "",
        targetName = this["targetName"] as? String ?: "Everyone",
        sentAt = long("sentAt"),
        readAt = null,
        updatedAt = long("updatedAt", long("sentAt")),
        deletedAt = (this["deletedAt"] as? Number)?.toLong(),
        dirty = false,
    )
}

private fun AuditLogEntity.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "action" to action,
    "entityId" to entityId,
    "businessDateEpochDay" to businessDateEpochDay,
    "actorId" to actorId,
    "actorName" to actorName,
    "occurredAt" to occurredAt,
    "beforeSummary" to beforeSummary,
    "afterSummary" to afterSummary,
)

private fun Map<String, Any?>.toAuditLog(): AuditLogEntity? {
    val id = this["id"] as? String ?: return null
    return AuditLogEntity(
        id = id,
        action = this["action"] as? String ?: "TALLY_EDITED",
        entityId = this["entityId"] as? String ?: "",
        businessDateEpochDay = long("businessDateEpochDay"),
        actorId = this["actorId"] as? String ?: "",
        actorName = this["actorName"] as? String ?: "Admin",
        occurredAt = long("occurredAt"),
        beforeSummary = this["beforeSummary"] as? String ?: "",
        afterSummary = this["afterSummary"] as? String ?: "",
        dirty = false,
    )
}

/** Firestore hands back numbers as Long or Double depending on how they were written. */
private fun Map<String, Any?>.long(key: String, fallback: Long = 0L): Long =
    (this[key] as? Number)?.toLong() ?: fallback

private fun Map<String, Any?>.int(key: String, fallback: Int = 0): Int =
    (this[key] as? Number)?.toInt() ?: fallback
