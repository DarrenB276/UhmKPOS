package com.uhmk.pos.core.export

import com.uhmk.pos.core.db.ItemEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SalesHistoryCsvImporterTest {

    private val catalogue = listOf(
        ItemEntity(
            id = "ramen",
            name = "Samyang Carbonara",
            category = "Ramen",
            costCentavos = 5_000,
            costKnown = true,
            studentCentavos = 7_000,
            regularCentavos = 8_000,
        )
    )

    @Test
    fun pairedFilesBuildDatedCompletedVoidedAndReturnedReceipts() {
        val summary = """
            Item name,SKU,Category,Items sold,Gross sales,Items refunded,Refunds,Discounts,Net sales,Cost of goods,Gross profit,Margin,Taxes
            Samyang Carbonara,1,Ramen,2,160.00,1,80.00,10.00,70.00,0,0,0,0
        """.trimIndent()
        val receipts = """
            Date,Receipt number,Receipt type,Gross sales,Discounts,Net sales,Taxes,Total collected,Cost of goods,Gross profit,Payment type,Description,Dining option,POS,Store,Cashier name,Customer name,Customer contacts,Status
            8/17/26 12:16 PM,1-0003,Sale,160.00,10.00,150.00,0,150.00,0,0,Cash,2 x Samyang Carbonara,Dine in,POS,Store,Owner,,,Closed
            8/17/26 12:20 PM,1-0004,Sale,80.00,0,80.00,0,80.00,0,0,Cash,1 x Samyang Carbonara,Takeout,POS,Store,Owner,,,Cancelled
            8/17/26 12:30 PM,1-0002,Refund,-80.00,0,-80.00,0,-80.00,0,0,Card,1 x Samyang Carbonara,Dine in,POS,Store,Owner,,,Closed
        """.trimIndent()

        val plan = SalesHistoryCsvImporter.plan(listOf(receipts, summary), catalogue)

        assertEquals(1, plan.completedReceipts)
        assertEquals(1, plan.voidedReceipts)
        assertEquals(1, plan.returnedReceipts)
        assertEquals(2, plan.unitsSold)
        assertEquals(16_000, plan.grossCentavos)
        assertEquals(1_000, plan.discountCentavos)
        assertEquals(8_000, plan.refundCentavos)
        assertEquals(7_000, plan.netAfterRefundsCentavos)
        assertEquals(16_000, plan.receipts.first { it.status == ImportedReceiptStatus.COMPLETED }
            .lines.sumOf { it.unitPriceCentavos })
    }

    @Test
    fun exactAllocationAlwaysReconciles() {
        assertEquals(listOf(34L, 33L, 33L), SalesHistoryCsvImporter.allocateExact(100, listOf(1, 1, 1)))
        assertEquals(100L, SalesHistoryCsvImporter.allocateExact(100, listOf(7, 2, 1)).sum())
        assertEquals(listOf(2L, 2L, 1L), SalesHistoryCsvImporter.allocateExact(5, listOf(0, 0, 0)))
    }
}
