package com.uhmk.pos.core.sync

import com.uhmk.pos.core.db.ItemEntity

/**
 * Pure catalogue conflict rules, kept separate from Firebase so the dangerous cases are unit
 * testable. Only fields deliberately changed by a person are allowed to travel back to cloud.
 */
object ItemSyncPolicy {

    const val NAME = "name"
    const val CATEGORY = "category"
    const val SKU = "sku"
    const val TRACK_STOCK = "trackStock"
    const val COST = "costCentavos"
    const val COST_KNOWN = "costKnown"
    const val STUDENT_PRICE = "studentCentavos"
    const val REGULAR_PRICE = "regularCentavos"
    const val BOX_COST = "boxCostCentavos"
    const val UNITS_PER_BOX = "unitsPerBox"
    const val STOCK = "stockQty"
    const val LOW_STOCK_AT = "lowStockAt"
    const val ACTIVE = "active"
    const val SORT_INDEX = "sortIndex"

    val ALL_FIELDS: Set<String> = linkedSetOf(
        NAME,
        CATEGORY,
        SKU,
        TRACK_STOCK,
        COST,
        COST_KNOWN,
        STUDENT_PRICE,
        REGULAR_PRICE,
        BOX_COST,
        UNITS_PER_BOX,
        STOCK,
        LOW_STOCK_AT,
        ACTIVE,
        SORT_INDEX,
    )

    val COST_FIELDS: Set<String> = setOf(COST, COST_KNOWN)

    data class MergeResult(
        val item: ItemEntity,
        val hadVersionConflict: Boolean,
        val protectedKnownCost: Boolean,
    )

    fun decode(encoded: String): Set<String> = encoded.split(',')
        .map(String::trim)
        .filter { it in ALL_FIELDS }
        .toCollection(linkedSetOf())

    fun encode(fields: Collection<String>): String = ALL_FIELDS
        .filter(fields::contains)
        .joinToString(",")

    fun changedFields(before: ItemEntity?, after: ItemEntity): Set<String> {
        if (before == null) return ALL_FIELDS
        return buildSet {
            if (before.name != after.name) add(NAME)
            if (before.category != after.category) add(CATEGORY)
            if (before.sku != after.sku) add(SKU)
            if (before.trackStock != after.trackStock) add(TRACK_STOCK)
            if (before.costCentavos != after.costCentavos || before.costKnown != after.costKnown) {
                addAll(COST_FIELDS)
            }
            if (before.studentCentavos != after.studentCentavos) add(STUDENT_PRICE)
            if (before.regularCentavos != after.regularCentavos) add(REGULAR_PRICE)
            if (before.boxCostCentavos != after.boxCostCentavos) add(BOX_COST)
            if (before.unitsPerBox != after.unitsPerBox) add(UNITS_PER_BOX)
            if (before.stockQty != after.stockQty) add(STOCK)
            if (before.lowStockAt != after.lowStockAt) add(LOW_STOCK_AT)
            if (before.active != after.active) add(ACTIVE)
            if (before.sortIndex != after.sortIndex) add(SORT_INDEX)
        }
    }

    /** Preserves the cloud baseline and records only the user's actual changes. */
    fun prepareLocalChange(before: ItemEntity?, after: ItemEntity, now: Long): ItemEntity {
        val changed = changedFields(before, after)
        val pending = decode(before?.pendingFields.orEmpty()) + changed
        return after.copy(
            updatedAt = if (changed.isNotEmpty()) now else before?.updatedAt ?: after.updatedAt,
            cloudVersion = before?.cloudVersion ?: 0,
            pendingFields = encode(pending),
            pendingStockDelta = if (STOCK in changed) 0 else before?.pendingStockDelta ?: 0,
            stockAbsolutePending = if (STOCK in changed) true
            else before?.stockAbsolutePending ?: false,
            dirty = pending.isNotEmpty(),
        )
    }

    /**
     * Applies a cloud snapshot without discarding intentional local fields.
     *
     * A known supplier cost is one-way protected: an old blank cache may never turn it back into
     * "not set". A genuine zero-cost service is still valid because it carries costKnown=true.
     */
    fun mergeRemote(remote: ItemEntity, local: ItemEntity): MergeResult {
        val originalPending = decode(local.pendingFields)
        val protectedCost = remote.costKnown && !local.costKnown &&
            originalPending.any(COST_FIELDS::contains)
        val accepted = if (protectedCost) originalPending - COST_FIELDS else originalPending
        var mergedFields = applyFields(remote, local, accepted)
        if (STOCK in accepted && !local.stockAbsolutePending) {
            mergedFields = mergedFields.copy(
                stockQty = (remote.stockQty + local.pendingStockDelta).coerceAtLeast(0)
            )
        }
        val merged = mergedFields.copy(
            imagePath = local.imagePath,
            updatedAt = if (accepted.isEmpty()) remote.updatedAt
            else maxOf(remote.updatedAt, local.updatedAt),
            cloudVersion = remote.cloudVersion,
            pendingFields = encode(accepted),
            pendingStockDelta = local.pendingStockDelta,
            stockAbsolutePending = local.stockAbsolutePending,
            dirty = accepted.isNotEmpty(),
        )
        return MergeResult(
            item = merged,
            hadVersionConflict = originalPending.isNotEmpty() &&
                remote.cloudVersion > local.cloudVersion,
            protectedKnownCost = protectedCost,
        )
    }

    fun applyFields(base: ItemEntity, source: ItemEntity, fields: Set<String>): ItemEntity {
        var result = base
        if (NAME in fields) result = result.copy(name = source.name)
        if (CATEGORY in fields) result = result.copy(category = source.category)
        if (SKU in fields) result = result.copy(sku = source.sku)
        if (TRACK_STOCK in fields) result = result.copy(trackStock = source.trackStock)
        if (COST in fields || COST_KNOWN in fields) result = result.copy(
            costCentavos = source.costCentavos,
            costKnown = source.costKnown,
        )
        if (STUDENT_PRICE in fields) result = result.copy(studentCentavos = source.studentCentavos)
        if (REGULAR_PRICE in fields) result = result.copy(regularCentavos = source.regularCentavos)
        if (BOX_COST in fields) result = result.copy(boxCostCentavos = source.boxCostCentavos)
        if (UNITS_PER_BOX in fields) result = result.copy(unitsPerBox = source.unitsPerBox)
        if (STOCK in fields) result = result.copy(stockQty = source.stockQty)
        if (LOW_STOCK_AT in fields) result = result.copy(lowStockAt = source.lowStockAt)
        if (ACTIVE in fields) result = result.copy(active = source.active)
        if (SORT_INDEX in fields) result = result.copy(sortIndex = source.sortIndex)
        return result
    }

    fun cloudValues(item: ItemEntity, fields: Set<String>): Map<String, Any?> = buildMap {
        if (NAME in fields) put(NAME, item.name)
        if (CATEGORY in fields) put(CATEGORY, item.category)
        if (SKU in fields) put(SKU, item.sku)
        if (TRACK_STOCK in fields) put(TRACK_STOCK, item.trackStock)
        if (COST in fields || COST_KNOWN in fields) {
            put(COST, item.costCentavos)
            put(COST_KNOWN, item.costKnown)
        }
        if (STUDENT_PRICE in fields) put(STUDENT_PRICE, item.studentCentavos)
        if (REGULAR_PRICE in fields) put(REGULAR_PRICE, item.regularCentavos)
        if (BOX_COST in fields) put(BOX_COST, item.boxCostCentavos)
        if (UNITS_PER_BOX in fields) put(UNITS_PER_BOX, item.unitsPerBox)
        if (STOCK in fields) put(STOCK, item.stockQty)
        if (LOW_STOCK_AT in fields) put(LOW_STOCK_AT, item.lowStockAt)
        if (ACTIVE in fields) put(ACTIVE, item.active)
        if (SORT_INDEX in fields) put(SORT_INDEX, item.sortIndex)
    }
}
