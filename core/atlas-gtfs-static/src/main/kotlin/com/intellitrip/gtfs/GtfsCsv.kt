package com.intellitrip.gtfs

/**
 * Streaming CSV reader for GTFS tables.
 *
 * Rows are delivered one at a time and never retained, so parsing a table costs
 * one row's worth of memory rather than one copy of the whole file. This keeps
 * large tables such as `stop_times.txt` within an Android heap.
 */
internal object GtfsCsv {

    class Table(val header: List<String>, val rows: List<Map<String, String>>) {
        fun hasColumn(name: String): Boolean = name in header
    }

    /** Column index lookup used by the streaming parser to avoid per-row maps. */
    class Columns(val names: List<String>) {
        private val indexByName: Map<String, Int> =
            names.mapIndexed { position, name -> name to position }.toMap()

        fun indexOf(name: String): Int = indexByName[name] ?: -1
    }

    fun parse(content: String): Table {
        val records = splitRecords(content)
        if (records.isEmpty()) return Table(emptyList(), emptyList())
        val header = records.first().map { it.trim() }
        val rows = records.drop(1)
            .filter { it.isNotEmpty() }
            .map { record -> header.mapIndexed { i, name -> name to (record.getOrNull(i)?.trim() ?: "") }.toMap() }
        return Table(header, rows)
    }

    /**
     * Streams rows from raw table bytes. [onRow] receives the column lookup and
     * the field values; neither is retained after the callback returns.
     */
    fun forEachRow(
        bytes: ByteArray,
        onHeader: (Columns) -> Unit,
        onRow: (Columns, List<String>) -> Unit,
    ) {
        val records = ByteRecordReader(bytes)
        var columns: Columns? = null
        while (true) {
            val record = records.nextRecord() ?: break
            if (record.isEmpty()) continue
            if (columns == null) {
                val header = record.map { it.trim() }
                columns = Columns(header)
                onHeader(columns)
            } else {
                onRow(columns, record)
            }
        }
    }

    fun value(values: List<String>, columns: Columns, name: String): String? {
        val index = columns.indexOf(name)
        if (index < 0) return null
        return values.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun int(values: List<String>, columns: Columns, name: String): Int? = value(values, columns, name)?.toIntOrNull()

    fun double(values: List<String>, columns: Columns, name: String): Double? =
        value(values, columns, name)?.toDoubleOrNull()

    private fun splitRecords(content: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val reader = ByteRecordReader(content.toByteArray(Charsets.UTF_8))
        while (true) {
            records += reader.nextRecord() ?: break
        }
        return records
    }
}

/**
 * Reads CSV records from raw bytes without materializing the whole document.
 * Handles quoted fields and embedded newlines inside quotes.
 */
internal class ByteRecordReader(private val bytes: ByteArray) {

    private var index = 0

    fun nextRecord(): List<String>? {
        if (index >= bytes.size) return null
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var sawAny = false
        while (index < bytes.size) {
            val c = bytes[index].toInt().toChar()
            when {
                inQuotes && c == '"' && index + 1 < bytes.size &&
                    bytes[index + 1].toInt().toChar() == '"' -> {
                    field.append('"')
                    index++
                }

                c == '"' -> inQuotes = !inQuotes

                !inQuotes && c == ',' -> {
                    fields.add(field.toString())
                    field.setLength(0)
                    sawAny = true
                }

                !inQuotes && (c == '\n') -> {
                    fields.add(field.toString())
                    index++
                    return finish(fields, sawAny)
                }

                c != '\r' -> field.append(c)
            }
            sawAny = true
            index++
        }
        if (field.isNotEmpty() || fields.isNotEmpty()) {
            fields.add(field.toString())
            return finish(fields, sawAny)
        }
        return null
    }

    private fun finish(fields: List<String>, sawAny: Boolean): List<String> =
        if (!sawAny && fields.all { it.isBlank() }) emptyList() else fields
}