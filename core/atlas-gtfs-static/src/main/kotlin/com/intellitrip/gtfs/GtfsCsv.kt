package com.intellitrip.gtfs

/**
 * Minimal RFC 4180-style CSV reader tailored for GTFS tables. GTFS uses
 * comma-separated values with optional quoting; values are returned as trimmed
 * strings with empty string for missing optional fields.
 */
internal object GtfsCsv {

    class Table(val header: List<String>, val rows: List<Map<String, String>>) {
        fun hasColumn(name: String): Boolean = name in header
    }

    fun parse(content: String): Table {
        val lines = splitRecords(content)
        if (lines.isEmpty()) return Table(emptyList(), emptyList())
        val header = lines.first().map { it.trim() }
        val rows = lines.drop(1)
            .filter { it.isNotEmpty() }
            .map { record ->
                header.mapIndexed { index, name -> name to (record.getOrNull(index)?.trim() ?: "") }
                    .toMap()
            }
        return Table(header, rows)
    }

    fun read(row: Map<String, String>, column: String): String? =
        row[column]?.takeIf { it.isNotEmpty() }

    fun readInt(row: Map<String, String>, column: String): Int? =
        read(row, column)?.toIntOrNull()

    fun readDouble(row: Map<String, String>, column: String): Double? =
        read(row, column)?.toDoubleOrNull()

    private fun splitRecords(content: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < content.length) {
            val c = content[index]
            when {
                inQuotes && c == '"' && index + 1 < content.length && content[index + 1] == '"' -> {
                    field.append('"'); index++
                }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> {
                    current.add(field.toString()); field.clear()
                }
                !inQuotes && c == '\n' -> {
                    current.add(field.toString()); field.clear()
                    records.add(current); current = mutableListOf()
                }
                c != '\r' -> field.append(c)
            }
            index++
        }
        if (field.isNotEmpty() || current.isNotEmpty()) {
            current.add(field.toString())
            records.add(current)
        }
        return records
    }
}