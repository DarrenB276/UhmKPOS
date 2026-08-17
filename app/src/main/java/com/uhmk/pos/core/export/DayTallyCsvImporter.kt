package com.uhmk.pos.core.export

import com.uhmk.pos.core.db.ItemEntity
import com.uhmk.pos.core.model.PriceTier
import com.uhmk.pos.core.repo.TallyImportLine
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class DayTallyImportPlan(
    val lines: List<TallyImportLine>,
    /**
     * Every date the file covers, including days listed with no sales.
     *
     * A closed day is information too. Listing it with zero quantities is how the file says "this
     * date really had nothing", which clears whatever tally was there rather than leaving a stale
     * figure behind.
     */
    val dates: List<LocalDate>,
    val rowsRead: Int,
    val unmatchedNames: List<String>,
    val invalidRows: Int,
) {
    val units: Int get() = lines.sumOf { it.qty }
    val emptyDates: List<LocalDate> get() = dates - lines.map { it.date }.toSet()
}

/**
 * Reads a day-tally file: one row per product, per tier, per date.
 *
 * Expected columns are `date`, `qty` and one of `itemId` / `sku` / `name`, plus an optional `tier`.
 * Anything else in the file is ignored, so a spreadsheet exported from a back office can be handed
 * over with its extra money columns still attached.
 */
object DayTallyCsvImporter {

    private val DATE_FORMATS = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("d/M/yyyy"),
        DateTimeFormatter.ofPattern("M/d/yyyy"),
        DateTimeFormatter.ofPattern("d-MMM-yyyy"),
        DateTimeFormatter.ofPattern("MMM d, yyyy"),
    )

    fun plan(content: String, catalogue: List<ItemEntity>): DayTallyImportPlan {
        val table = CsvTable.parse(content.removePrefix("﻿"))
            .filter { row -> row.any(String::isNotBlank) }
        require(table.isNotEmpty()) { "The file is empty" }

        val headers = table.first().map(CsvTable::normaliseHeader)
        require("date" in headers) { "This file has no date column, so there is nothing to date." }
        require(headers.any { it in setOf("itemid", "sku", "name", "item", "product") }) {
            "This file has no item, sku or name column."
        }
        require(headers.any { it in setOf("qty", "quantity", "itemssold", "units", "sold") }) {
            "This file has no quantity column."
        }

        val byId = catalogue.associateBy { it.id }
        val bySku = catalogue.filter { it.sku.isNotBlank() }.groupBy { it.sku.trim().lowercase() }
        val byName = catalogue.groupBy { it.name.trim().lowercase() }

        val lines = mutableListOf<TallyImportLine>()
        val unmatched = sortedSetOf<String>()
        val datesSeen = sortedSetOf<LocalDate>()
        var invalid = 0

        table.drop(1).forEach { cells ->
            val values = headers.mapIndexed { index, header ->
                header to cells.getOrElse(index) { "" }.trim()
            }.toMap()

            val date = values["date"]?.let(::parseDate)
            val qty = listOf("qty", "quantity", "itemssold", "units", "sold")
                .firstNotNullOfOrNull { values[it]?.takeIf(String::isNotBlank) }
                ?.let { it.toDoubleOrNull()?.toInt() }

            if (date == null || qty == null) {
                invalid++
                return@forEach
            }
            datesSeen += date
            // A zero row is a product that did not sell, not a mistake. Nothing to record.
            if (qty <= 0) return@forEach

            val label = listOf("name", "item", "product", "sku", "itemid")
                .firstNotNullOfOrNull { values[it]?.takeIf(String::isNotBlank) }.orEmpty()

            val item = values["itemid"]?.takeIf(String::isNotBlank)?.let(byId::get)
                ?: values["sku"]?.takeIf(String::isNotBlank)
                    ?.let { bySku[it.lowercase()]?.singleOrNull() }
                ?: listOf("name", "item", "product")
                    .firstNotNullOfOrNull { values[it]?.takeIf(String::isNotBlank) }
                    ?.let { byName[it.lowercase()]?.singleOrNull() }

            if (item == null) {
                unmatched += label
                return@forEach
            }

            lines += TallyImportLine(
                date = date,
                itemId = item.id,
                tier = parseTier(values["tier"], label),
                qty = qty,
            )
        }

        // Two rows for the same product, tier and day are added together rather than fighting.
        val merged = lines
            .groupBy { Triple(it.date, it.itemId, it.tier) }
            .map { (key, rows) ->
                TallyImportLine(key.first, key.second, key.third, rows.sumOf { it.qty })
            }
            .sortedWith(compareBy({ it.date }, { it.itemId }, { it.tier }))

        return DayTallyImportPlan(
            lines = merged,
            dates = datesSeen.toList(),
            rowsRead = table.size - 1,
            unmatchedNames = unmatched.toList(),
            invalidRows = invalid,
        )
    }

    private fun parseDate(raw: String): LocalDate? {
        if (raw.isBlank()) return null
        val cleaned = raw.trim().substringBefore(' ').takeIf { it.contains('-') || it.contains('/') }
            ?: raw.trim()
        for (format in DATE_FORMATS) {
            try {
                return LocalDate.parse(cleaned, format)
            } catch (_: DateTimeParseException) {
                // Try the next shape.
            }
        }
        return null
    }

    /**
     * An explicit tier column wins; otherwise the label decides.
     *
     * Student rows arrive from the back office as separate products with a marker on the name,
     * because that system has no concept of two prices for one product. Here they are the same
     * product at the student price.
     */
    private fun parseTier(explicit: String?, label: String): PriceTier {
        explicit?.trim()?.lowercase()?.let {
            when {
                it.startsWith("stu") -> return PriceTier.STUDENT
                it.startsWith("reg") -> return PriceTier.REGULAR
            }
        }
        val name = label.lowercase()
        return if (name.startsWith("stu ") || name.contains("student")) PriceTier.STUDENT
        else PriceTier.REGULAR
    }
}
