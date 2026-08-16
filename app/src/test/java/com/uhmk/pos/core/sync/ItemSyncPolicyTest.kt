package com.uhmk.pos.core.sync

import com.uhmk.pos.core.db.ItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemSyncPolicyTest {

    @Test
    fun staleBlankCostCannotEraseKnownCloudCost() {
        val remote = item(cost = 6_000, known = true, version = 18)
        val stale = item(cost = 0, known = false, version = 10).copy(
            pendingFields = ItemSyncPolicy.encode(ItemSyncPolicy.COST_FIELDS),
            dirty = true,
        )

        val result = ItemSyncPolicy.mergeRemote(remote, stale)

        assertEquals(6_000, result.item.costCentavos)
        assertTrue(result.item.costKnown)
        assertTrue(result.protectedKnownCost)
        assertTrue(result.hadVersionConflict)
        assertFalse(result.item.dirty)
        assertEquals("", result.item.pendingFields)
    }

    @Test
    fun staleNameEditMergesWithoutTouchingCloudCost() {
        val remote = item(cost = 6_000, known = true, version = 18)
        val stale = item(cost = 0, known = false, version = 10).copy(
            name = "Updated product name",
            pendingFields = ItemSyncPolicy.NAME,
            dirty = true,
        )

        val result = ItemSyncPolicy.mergeRemote(remote, stale)

        assertEquals("Updated product name", result.item.name)
        assertEquals(6_000, result.item.costCentavos)
        assertTrue(result.item.costKnown)
        assertEquals(ItemSyncPolicy.NAME, result.item.pendingFields)
        assertTrue(result.hadVersionConflict)
        assertFalse(result.protectedKnownCost)
    }

    @Test
    fun localEditQueuesOnlyFieldsThatChanged() {
        val original = item(cost = 6_000, known = true, version = 4)
        val changed = original.copy(stockQty = 12)

        val queued = ItemSyncPolicy.prepareLocalChange(original, changed, now = 1234)

        assertEquals(ItemSyncPolicy.STOCK, queued.pendingFields)
        assertEquals(4, queued.cloudVersion)
        assertEquals(1234, queued.updatedAt)
        assertTrue(queued.dirty)
    }

    @Test
    fun bundledItemIsNotAnUploadUntilExplicitlyCreated() {
        val bundled = item(cost = 0, known = false, version = 0)
        val queued = ItemSyncPolicy.prepareLocalChange(null, bundled, now = 55)

        assertEquals(ItemSyncPolicy.encode(ItemSyncPolicy.ALL_FIELDS), queued.pendingFields)
        assertTrue(queued.dirty)
    }

    @Test
    fun simultaneousSalesMergeAsStockDelta() {
        val cloudAfterOtherTill = item(cost = 6_000, known = true, version = 6)
            .copy(stockQty = 9)
        val thisTill = item(cost = 6_000, known = true, version = 5).copy(
            stockQty = 9,
            pendingFields = ItemSyncPolicy.STOCK,
            pendingStockDelta = -1,
            stockAbsolutePending = false,
            dirty = true,
        )

        val result = ItemSyncPolicy.mergeRemote(cloudAfterOtherTill, thisTill)

        assertEquals(8, result.item.stockQty)
        assertEquals(-1, result.item.pendingStockDelta)
        assertTrue(result.hadVersionConflict)
    }

    private fun item(cost: Long, known: Boolean, version: Long) = ItemEntity(
        id = "itm-test",
        name = "Test product",
        category = "Food",
        sku = "TEST-1",
        costCentavos = cost,
        costKnown = known,
        studentCentavos = 8_000,
        regularCentavos = 9_000,
        stockQty = 5,
        cloudVersion = version,
        dirty = false,
    )
}
