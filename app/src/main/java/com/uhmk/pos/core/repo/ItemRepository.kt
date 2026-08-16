package com.uhmk.pos.core.repo

import android.content.Context
import android.net.Uri
import com.uhmk.pos.core.db.CatalogueSeeder
import com.uhmk.pos.core.db.CategoryCount
import com.uhmk.pos.core.db.ItemDao
import com.uhmk.pos.core.db.ItemEntity
import com.uhmk.pos.core.export.InventoryCsvImporter
import com.uhmk.pos.core.export.InventoryImportPlan
import com.uhmk.pos.core.sync.ItemSyncPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class ItemRepository(
    private val context: Context,
    private val dao: ItemDao,
) {

    fun observeAll(): Flow<List<ItemEntity>> = dao.observeAll()
    fun observeActive(): Flow<List<ItemEntity>> = dao.observeActive()
    fun observeById(id: String): Flow<ItemEntity?> = dao.observeById(id)
    fun observeCategories(): Flow<List<String>> = dao.observeCategories()
    fun observeCategoryCounts(): Flow<List<CategoryCount>> = dao.observeCategoryCounts()
    fun observeMissingCostCount(): Flow<Int> = dao.observeMissingCostCount()

    suspend fun getById(id: String): ItemEntity? = dao.getById(id)
    suspend fun getBySku(sku: String): ItemEntity? = dao.getBySku(sku.trim())

    suspend fun save(item: ItemEntity) {
        val previous = dao.getById(item.id)
        dao.upsert(ItemSyncPolicy.prepareLocalChange(previous, item, System.currentTimeMillis()))
    }

    suspend fun delete(item: ItemEntity) {
        item.imagePath?.let { runCatching { File(it).delete() } }
        dao.delete(item)
    }

    /** Quick cost entry, used by the "needs cost" list. */
    suspend fun setCost(id: String, costCentavos: Long) =
        dao.setCost(id, costCentavos.coerceAtLeast(0), System.currentTimeMillis())

    suspend fun setStock(id: String, qty: Int) =
        dao.setStock(id, qty.coerceAtLeast(0), System.currentTimeMillis())

    suspend fun addStock(id: String, qty: Int) =
        dao.addStock(id, qty, System.currentTimeMillis())

    suspend fun restockBoxes(id: String, boxes: Int) {
        val item = dao.getById(id) ?: return
        dao.addStock(id, item.unitsPerBox * boxes, System.currentTimeMillis())
    }

    suspend fun renameCategory(from: String, to: String) {
        val target = to.trim()
        if (target.isEmpty() || target == from) return
        dao.renameCategory(from, target, System.currentTimeMillis())
    }

    suspend fun countItems(): Int = dao.count()

    /** Restores matching inventory rows and queues only fields that actually changed. */
    suspend fun importInventoryCsv(source: Uri): InventoryImportPlan {
        val content = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(source)?.bufferedReader()?.use { it.readText() }
                ?: error("Could not open that CSV file")
        }
        val plan = InventoryCsvImporter.plan(content, dao.getAll())
        plan.items.forEach { save(it) }
        return plan
    }

    /**
     * Seeds the catalogue if it is empty. [force] re-applies the shipped catalogue over existing
     * rows, which is what "Reload built-in price list" in Settings uses.
     */
    suspend fun seedIfEmpty(force: Boolean = false): Int = withContext(Dispatchers.IO) {
        if (!force && dao.count() > 0) return@withContext 0
        val seeded = CatalogueSeeder.loadFromAssets(context)

        if (force) {
            val merged = seeded.map { fresh ->
                val old = dao.getById(fresh.id) ?: return@map fresh
                fresh.copy(
                    imagePath = old.imagePath,
                    stockQty = old.stockQty,
                    category = old.category,
                    trackStock = old.trackStock,
                    active = old.active,
                    // A cost the user typed in is worth more than the blank the export shipped.
                    costCentavos = if (fresh.costKnown) fresh.costCentavos else old.costCentavos,
                    costKnown = fresh.costKnown || old.costKnown,
                    // Regular prices are maintained in-app and must survive a catalogue reload.
                    regularCentavos = old.regularCentavos.takeIf { it > 0 }
                        ?: fresh.regularCentavos,
                    lowStockAt = old.lowStockAt,
                )
            }
            // Reload is an explicit admin action, but only the values that actually changed are
            // queued. Blank bundled costs can never replace costs already entered by the store.
            for (item in merged) save(item)
        } else {
            dao.upsertAll(seeded)
        }
        seeded.size
    }

    /**
     * Copies a picked photo into app-private storage and returns the stored path.
     *
     * Photo Picker hands back a short-lived permission grant on the original Uri, so the bytes
     * have to be copied now or the image breaks the next time the app opens.
     */
    suspend fun storeImage(source: Uri, itemId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "item_images").apply { mkdirs() }
            val target = File(dir, "$itemId-${UUID.randomUUID().toString().take(8)}.jpg")
            context.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching null

            dao.getById(itemId)?.imagePath
                ?.takeIf { it != target.absolutePath }
                ?.let { old -> runCatching { File(old).delete() } }

            target.absolutePath
        }.getOrNull()
    }

    suspend fun clearImage(itemId: String) {
        val item = dao.getById(itemId) ?: return
        item.imagePath?.let { runCatching { File(it).delete() } }
        // Photos are device-private on the free Firebase plan and must not dirty the cloud row.
        dao.upsert(item.copy(imagePath = null))
    }

    fun newItemTemplate(): ItemEntity = ItemEntity(
        id = "itm-" + UUID.randomUUID().toString().take(12),
        name = "",
        category = "General",
        sku = "",
        costCentavos = 0,
        costKnown = false,
        studentCentavos = 0,
        regularCentavos = 0,
        stockQty = 0,
        lowStockAt = 5,
        trackStock = true,
    )
}
