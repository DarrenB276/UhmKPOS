package com.uhmk.pos.core.export

import com.uhmk.pos.core.db.ItemEntity
import com.uhmk.pos.core.money.Money

data class InventoryImportPlan(
    val items: List<ItemEntity>,
    val rowsRead: Int,
    val unchanged: Int,
    val unmatched: Int,
    val invalidValues: Int,
)

/** Reads both the old inventory export and the safer v2.6.1 export containing stable item IDs. */
object InventoryCsvImporter {

    fun plan(content: String, current: List<ItemEntity>): InventoryImportPlan {
        val table = parseCsv(content.removePrefix("\uFEFF"))
        require(table.isNotEmpty()) { "The CSV file is empty" }
        val headers = table.first().map(::normaliseHeader)
        require("item" in headers || "itemid" in headers) {
            "This is not an inventory CSV exported by UhmK POS"
        }

        val byId = current.associateBy { it.id }
        val bySku = current.filter { it.sku.isNotBlank() }
            .groupBy { it.sku.trim().lowercase() }
        val byName = current.groupBy { it.name.trim().lowercase() }

        var unchanged = 0
        var unmatched = 0
        var invalid = 0
        val changes = mutableListOf<ItemEntity>()

        table.drop(1).filter { row -> row.any(String::isNotBlank) }.forEach { cells ->
            val values = headers.mapIndexed { index, header ->
                header to cells.getOrElse(index) { "" }.trim()
            }.toMap()
            val item = values["itemid"]?.takeIf(String::isNotBlank)?.let(byId::get)
                ?: values["sku"]?.takeIf(String::isNotBlank)
                    ?.let { bySku[it.lowercase()]?.singleOrNull() }
                ?: values["item"]?.takeIf(String::isNotBlank)
                    ?.let { name ->
                        val matches = byName[name.lowercase()].orEmpty()
                        val category = values["category"].orEmpty()
                        if (matches.size == 1) matches.single()
                        else matches.singleOrNull { it.category.equals(category, true) }
                    }

            val matched = item ?: run {
                unmatched++
                return@forEach
            }

            var updated = matched
            values.present("item")?.takeIf(String::isNotBlank)?.let { updated = updated.copy(name = it) }
            values.present("sku")?.let { updated = updated.copy(sku = it) }
            values.present("category")?.takeIf(String::isNotBlank)
                ?.let { updated = updated.copy(category = it) }

            values.present("unitcost")?.let { raw ->
                if (!raw.isUnknown()) {
                    Money.parse(raw)?.takeIf { it >= 0 }?.let {
                        updated = updated.copy(costCentavos = it, costKnown = true)
                    } ?: invalid++
                }
            }
            values.present("studentprice")?.let { raw ->
                Money.parse(raw)?.takeIf { it >= 0 }?.let {
                    updated = updated.copy(studentCentavos = it)
                } ?: invalid++
            }
            values.present("regularprice")?.let { raw ->
                Money.parse(raw)?.takeIf { it >= 0 }?.let {
                    updated = updated.copy(regularCentavos = it)
                } ?: invalid++
            }
            values.present("boxcost")?.let { raw ->
                if (!raw.isUnknown()) {
                    Money.parse(raw)?.takeIf { it >= 0 }?.let {
                        updated = updated.copy(boxCostCentavos = it)
                    } ?: invalid++
                }
            }
            values.present("unitsperbox")?.let { raw ->
                raw.toIntOrNull()?.takeIf { it > 0 }?.let {
                    updated = updated.copy(unitsPerBox = it)
                } ?: invalid++
            }
            values.present("instock")?.let { raw ->
                if (!raw.isUnknown()) {
                    raw.toIntOrNull()?.takeIf { it >= 0 }?.let {
                        updated = updated.copy(stockQty = it)
                    } ?: invalid++
                }
            }
            values.present("warnat")?.let { raw ->
                if (!raw.isUnknown()) {
                    raw.toIntOrNull()?.takeIf { it >= 0 }?.let {
                        updated = updated.copy(lowStockAt = it)
                    } ?: invalid++
                }
            }
            values.present("tracksstock")?.toBooleanValue()?.let {
                updated = updated.copy(trackStock = it)
            }
            values.present("active")?.toBooleanValue()?.let {
                updated = updated.copy(active = it)
            }

            if (updated == matched) unchanged++ else changes.add(updated)
        }

        return InventoryImportPlan(
            items = changes,
            rowsRead = table.drop(1).count { row -> row.any(String::isNotBlank) },
            unchanged = unchanged,
            unmatched = unmatched,
            invalidValues = invalid,
        )
    }

    private fun Map<String, String>.present(key: String): String? =
        if (containsKey(key)) getValue(key) else null

    private fun String.isUnknown(): Boolean = isBlank() || equals("not set", true) ||
        equals("n/a", true) || equals("unknown", true)

    private fun String.toBooleanValue(): Boolean? = when (trim().lowercase()) {
        "yes", "true", "1", "active" -> true
        "no", "false", "0", "inactive" -> false
        else -> null
    }

    private fun normaliseHeader(value: String): String = value.trim().lowercase()
        .replace(Regex("[^a-z0-9]"), "")

    /** Small RFC-4180 parser: quoted commas, quotes and line breaks all survive round trips. */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                quoted && char == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    cell.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                !quoted && char == ',' -> {
                    row += cell.toString()
                    cell.clear()
                }
                !quoted && (char == '\n' || char == '\r') -> {
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    row += cell.toString()
                    cell.clear()
                    rows += row
                    row = mutableListOf()
                }
                else -> cell.append(char)
            }
            index++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            rows += row
        }
        return rows
    }
}
