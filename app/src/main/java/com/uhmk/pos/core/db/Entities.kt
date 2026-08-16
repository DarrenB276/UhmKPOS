package com.uhmk.pos.core.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.uhmk.pos.core.model.PriceTier
import com.uhmk.pos.core.model.SaleStatus
import com.uhmk.pos.core.model.UserRole

/**
 * A sellable product.
 *
 * The source export kept the student price as a separate "STU ..." item; here one item carries both
 * prices, so the same product is one row on the shelf and one row in the reports.
 *
 * [costCentavos] is the unit cost (SRP) — the capital that has to go back into restocking. It is
 * never reported as earnings. [costKnown] distinguishes a genuine zero-cost service from a
 * product whose cost has not been entered yet.
 */
@Entity(tableName = "items", indices = [Index("category"), Index("sku")])
data class ItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val sku: String = "",
    val costCentavos: Long,
    val costKnown: Boolean = false,
    val studentCentavos: Long,
    val regularCentavos: Long,
    val boxCostCentavos: Long = 0,
    val unitsPerBox: Int = 1,
    val stockQty: Int = 0,
    val lowStockAt: Int = 5,
    /** Services (cooking fee, frying, rice) are sold without ever depleting stock. */
    val trackStock: Boolean = true,
    val imagePath: String? = null,
    val active: Boolean = true,
    val sortIndex: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Last catalogue revision accepted from Firestore. Never derived from a device clock. */
    val cloudVersion: Long = 0,
    /** Comma-separated cloud fields intentionally changed on this device. */
    val pendingFields: String = "",
    /** Relative stock movement waiting for cloud, so simultaneous sales do not lose a unit. */
    val pendingStockDelta: Int = 0,
    /** True for a deliberate stock count/import; false for sale/restock deltas. */
    val stockAbsolutePending: Boolean = false,
    val dirty: Boolean = false,
) {
    fun priceFor(tier: PriceTier): Long =
        if (tier == PriceTier.REGULAR) regularCentavos else studentCentavos

    /** Null when the cost is unknown — callers must decide how to present that. */
    fun profitFor(tier: PriceTier): Long? =
        if (costKnown) priceFor(tier) - costCentavos else null

    val studentProfit: Long? get() = profitFor(PriceTier.STUDENT)
    val regularProfit: Long? get() = profitFor(PriceTier.REGULAR)

    fun profitPerBox(tier: PriceTier = PriceTier.STUDENT): Long? =
        profitFor(tier)?.let { it * unitsPerBox }

    val isLowStock: Boolean get() = trackStock && stockQty <= lowStockAt
    val hasTwoPrices: Boolean get() = regularCentavos != studentCentavos
}

@Entity(tableName = "sales", indices = [Index("soldAt"), Index("cashierId")])
data class SaleEntity(
    @PrimaryKey val id: String,
    val soldAt: Long,
    val cashierId: String,
    val cashierName: String,
    /** Sequential receipt number, for looking a sale up by its printed slip. */
    val receiptNo: Long = 0,
    /**
     * Short code of the device that rang this sale up.
     *
     * Receipt numbers are allocated from the local database, so two phones both start at 1 and
     * every number collides once their sales sync together. The code is stamped on at checkout and
     * never changes afterwards, so a slip printed on the counter phone still reads "A-0007" even
     * when viewed on the owner's phone.
     */
    val deviceCode: String = "",
    val grossCentavos: Long,
    val discountCentavos: Long,
    /** What the customer actually paid: gross - discount. */
    val netCentavos: Long,
    /** Capital tied up in the goods sold, counting only lines with a known cost. */
    val costCentavos: Long,
    /** Take-home from lines with a known cost. */
    val profitCentavos: Long,
    /** Revenue from lines whose cost is unknown; profit on these cannot be computed yet. */
    val unknownCostCentavos: Long = 0,
    val paymentMethod: String = "Cash",
    val tenderedCentavos: Long = 0,
    /** DINE_IN, TAKEOUT, or UNSPECIFIED for old/day-tally sales. */
    val orderType: String = "UNSPECIFIED",
    /** Optional table number, guest name, or takeout order name. */
    val orderLabel: String = "",
    val note: String = "",
    /** Used for multi-device last-write-wins sync, including old dated day-tally sales. */
    val updatedAt: Long = System.currentTimeMillis(),
    /** A synced tombstone. Null means active; non-null means voided and excluded from reports. */
    val voidedAt: Long? = null,
    /** A completed order that was returned/refunded and put back into inventory. */
    val returnedAt: Long? = null,
    val returnReason: String = "",
    /** POS for normal checkout, TALLY for an admin-recorded day sheet. */
    val source: String = "POS",
    val dirty: Boolean = true,
) {
    val hasUnknownCost: Boolean get() = unknownCostCentavos > 0
    val changeCentavos: Long get() = (tenderedCentavos - netCentavos).coerceAtLeast(0)

    /**
     * What to print and show: "A-0007" on a multi-device store, plain "7" on a single device.
     * Padded so receipts sort and read consistently once the store passes a hundred sales.
     */
    val receiptLabel: String
        get() = if (deviceCode.isBlank()) receiptNo.toString()
        else "$deviceCode-${receiptNo.toString().padStart(4, '0')}"
    val status: SaleStatus get() = when {
        returnedAt != null -> SaleStatus.RETURNED
        voidedAt != null -> SaleStatus.VOIDED
        else -> SaleStatus.COMPLETED
    }
}

/**
 * A line of a sale. Unit price and unit cost are *snapshots* taken at the moment of sale, so
 * editing an item's price later never rewrites past profit.
 *
 * A sale-level discount is distributed across lines at checkout so per-item report totals
 * reconcile exactly with the sale totals.
 */
@Entity(
    tableName = "sale_lines",
    foreignKeys = [
        ForeignKey(
            entity = SaleEntity::class,
            parentColumns = ["id"],
            childColumns = ["saleId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("saleId"), Index("itemId")],
)
data class SaleLineEntity(
    @PrimaryKey val id: String,
    val saleId: String,
    val itemId: String,
    val itemName: String,
    val category: String = "",
    val tier: PriceTier,
    val unitPriceCentavos: Long,
    val unitCostCentavos: Long,
    /** Snapshot of whether the cost was known at sale time. */
    val costKnown: Boolean = true,
    val qty: Int,
    val discountCentavos: Long = 0,
) {
    val grossCentavos: Long get() = unitPriceCentavos * qty
    val netCentavos: Long get() = grossCentavos - discountCentavos
    val costCentavos: Long get() = if (costKnown) unitCostCentavos * qty else 0
    /** Null when the cost was unknown, so nothing downstream invents a margin. */
    val profitCentavos: Long? get() = if (costKnown) netCentavos - costCentavos else null
}

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val uid: String,
    val email: String,
    val displayName: String,
    val role: UserRole,
    val active: Boolean = true,
    val fcmToken: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
)

@Entity(tableName = "notices", indices = [Index("sentAt")])
data class NoticeEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val senderName: String,
    /** Empty means every account; otherwise only this Firebase UID should receive it. */
    val targetUid: String = "",
    val targetName: String = "Everyone",
    val sentAt: Long,
    val readAt: Long? = null,
    val updatedAt: Long = sentAt,
    val deletedAt: Long? = null,
    /**
     * MESSAGE for something a person sent, ALERT for something the app raised itself
     * (missing costs, no passcode, a scheduled reminder).
     *
     * Alerts were previously only ever posted to the system tray, so the in-app list stayed empty
     * even while the phone showed three notifications. They now live here too, but stay on the
     * device that raised them — "set a passcode" is not news for the rest of the staff.
     */
    val kind: String = KIND_MESSAGE,
    val dirty: Boolean = true,
) {
    val isRead: Boolean get() = readAt != null
    val isDeleted: Boolean get() = deletedAt != null
    val isAlert: Boolean get() = kind == KIND_ALERT

    companion object {
        const val KIND_MESSAGE = "MESSAGE"
        const val KIND_ALERT = "ALERT"
    }
}

/** A cart deliberately set aside at the counter. Stock changes only when it becomes a sale. */
@Entity(tableName = "tickets", indices = [Index("updatedAt")])
data class TicketEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val ownerId: String,
    val ownerName: String,
    val discountCentavos: Long = 0,
    val note: String = "",
    val tenderedCentavos: Long = 0,
    val paymentMethod: String = "Cash",
    val orderType: String = "DINE_IN",
    val orderLabel: String = "",
)

@Entity(
    tableName = "ticket_lines",
    foreignKeys = [
        ForeignKey(
            entity = TicketEntity::class,
            parentColumns = ["id"],
            childColumns = ["ticketId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("ticketId"), Index("itemId")],
)
data class TicketLineEntity(
    @PrimaryKey val id: String,
    val ticketId: String,
    val itemId: String,
    val tier: PriceTier,
    val qty: Int,
)

@Entity(tableName = "audit_logs", indices = [Index("occurredAt"), Index("businessDateEpochDay")])
data class AuditLogEntity(
    @PrimaryKey val id: String,
    val action: String,
    val entityId: String,
    val businessDateEpochDay: Long,
    val actorId: String,
    val actorName: String,
    val occurredAt: Long,
    val beforeSummary: String,
    val afterSummary: String,
    val dirty: Boolean = true,
)
