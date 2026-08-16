package com.uhmk.pos.core.export

import com.uhmk.pos.core.db.ItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryCsvImporterTest {

    @Test
    fun restoresCostAndPricesByStableItemId() {
        val csv = """
            Item ID,SKU,Item,Category,Unit cost,Student price,Regular price,In stock,Warn at,Tracks stock,Active
            itm-ramen,R1,Ramen,Food,45.50,70.00,78.00,12,4,yes,yes
        """.trimIndent()

        val plan = InventoryCsvImporter.plan(csv, listOf(item()))

        assertEquals(1, plan.items.size)
        assertEquals(4_550, plan.items.single().costCentavos)
        assertTrue(plan.items.single().costKnown)
        assertEquals(7_000, plan.items.single().studentCentavos)
        assertEquals(7_800, plan.items.single().regularCentavos)
        assertEquals(12, plan.items.single().stockQty)
    }

    @Test
    fun unknownCsvCostNeverClearsExistingKnownCost() {
        val csv = """
            SKU,Item,Category,Unit cost,Student price,Regular price
            R1,Ramen,Food,not set,70.00,78.00
        """.trimIndent()

        val plan = InventoryCsvImporter.plan(csv, listOf(item()))
        val restored = plan.items.single()

        assertEquals(5_000, restored.costCentavos)
        assertTrue(restored.costKnown)
    }

    @Test
    fun quotedNamesWithCommasRoundTrip() {
        val csv = """
            Item ID,Item,Category,Unit cost
            itm-ramen,"Ramen, cheese",Food,55.00
        """.trimIndent()

        val plan = InventoryCsvImporter.plan(csv, listOf(item()))

        assertEquals("Ramen, cheese", plan.items.single().name)
        assertEquals(5_500, plan.items.single().costCentavos)
    }

    private fun item() = ItemEntity(
        id = "itm-ramen",
        name = "Ramen",
        category = "Food",
        sku = "R1",
        costCentavos = 5_000,
        costKnown = true,
        studentCentavos = 6_500,
        regularCentavos = 7_000,
        stockQty = 5,
        cloudVersion = 3,
    )
}
