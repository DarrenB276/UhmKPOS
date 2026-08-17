package com.uhmk.pos.core.export

/**
 * Shared CSV reading for the importers.
 *
 * Small RFC-4180 parser: quoted commas, quotes and line breaks all survive round trips. Kept in one
 * place so the inventory restore and the day-tally import cannot drift apart on what a comma means
 * inside a product name.
 */
object CsvTable {

    fun parse(text: String): List<List<String>> {
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

    /** Header cells compared without spacing or punctuation, so "Item ID" and "itemid" agree. */
    fun normaliseHeader(value: String): String = value.trim().lowercase()
        .replace(Regex("[^a-z0-9]"), "")
}
