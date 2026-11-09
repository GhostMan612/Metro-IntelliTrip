package com.intellitrip.gtfs

/**
 * Portable ZIP reader (archive offset table + raw DEFLATE/STORED inflate so
 * GTFS payloads decode identically on every target, including wasmJs).
 *
 * This replaces the platform ZIP streaming used by the old JVM-only module.
 * The central directory is the source of truth: entries are located by offset
 * and decoded on demand, so the parser can inflate one table at a time and
 * release it before inflating the next.
 *
 * Decode support: method 0 (stored) and 8 (deflated), per ZIP APPNOTE. Zip64
 * layout (`0xFFFF`/`0xFFFFFFFF` sentinels), encryption and bzip2/LZMA entries
 * raise [GtfsArchiveException] with a clear message.
 */
internal class ZipReader(private val bytes: ByteArray) {

    private class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Int,
        val uncompressedSize: Int,
        val localOffset: Int,
    )

    /** Full entry names of file entries (directory entries are skipped). */
    val names: List<String>
        get() = entries.map { it.name }

    /** Decodes the entry with the exact full [name]. */
    fun read(name: String): ByteArray? {
        val entry = entries.firstOrNull { it.name == name } ?: return null
        return decode(entry)
    }

    /** Decodes the first entry whose base name is [name], or null. */
    fun readByBasename(name: String): ByteArray? {
        val entry = entries.firstOrNull { it.name.substringAfterLast('/') == name } ?: return null
        return decode(entry)
    }

    fun hasBasename(name: String): Boolean =
        entries.any { it.name.substringAfterLast('/') == name }

    private val entries: List<Entry> = parseCentralDirectory()

    private fun decode(entry: Entry): ByteArray {
        val local = entry.localOffset
        if (local < 0 || local + 30 > bytes.size || u32(local) != LOCAL_HEADER) {
            throw GtfsArchiveException("corrupt ZIP local header for '${entry.name}'")
        }
        val nameLen = u16(local + 26)
        val extraLen = u16(local + 28)
        val dataStart = local + 30 + nameLen + extraLen
        val dataEnd = dataStart + entry.compressedSize
        if (dataEnd > bytes.size) {
            throw GtfsArchiveException("corrupt ZIP entry '${entry.name}': truncated")
        }
        val decoded = when (entry.method) {
            METHOD_STORED -> bytes.copyOfRange(dataStart, dataEnd)
            METHOD_DEFLATED -> inflate(bytes, dataStart, entry.compressedSize, entry.uncompressedSize, entry.name)
            else -> throw GtfsArchiveException(
                "unsupported ZIP compression method ${entry.method} for '${entry.name}'",
            )
        }
        if (decoded.size != entry.uncompressedSize) {
            throw GtfsArchiveException("corrupt ZIP entry '${entry.name}': size mismatch")
        }
        return decoded
    }

    private fun parseCentralDirectory(): List<Entry> {
        val eocd = findEocd()
        val total = u16(eocd + 10)
        val cdOffset = u32(eocd + 16)
        if (total == 0xFFFF || cdOffset == 0xFFFFFFFFL) {
            throw GtfsArchiveException("zip64 archives are not supported")
        }
        val result = ArrayList<Entry>(total)
        var offset = cdOffset.toInt()
        repeat(total) {
            if (offset < 0 || offset + 46 > bytes.size || u32(offset) != CENTRAL_HEADER) {
                throw GtfsArchiveException("corrupt ZIP central directory")
            }
            val flags = u16(offset + 8)
            if (flags and FLAG_ENCRYPTED != 0) {
                throw GtfsArchiveException("encrypted ZIP entries are not supported")
            }
            val method = u16(offset + 10)
            val compressedSize = u32(offset + 20)
            val uncompressedSize = u32(offset + 24)
            val nameLen = u16(offset + 28)
            val extraLen = u16(offset + 30)
            val commentLen = u16(offset + 32)
            val localOffset = u32(offset + 42)
            if (compressedSize > Int.MAX_VALUE || uncompressedSize > Int.MAX_VALUE || localOffset > Int.MAX_VALUE) {
                throw GtfsArchiveException("zip64 archives are not supported")
            }
            val nameStart = offset + 46
            val nameEnd = nameStart + nameLen
            if (nameEnd > bytes.size) {
                throw GtfsArchiveException("corrupt ZIP central directory entry")
            }
            val name = bytes.decodeToString(nameStart, nameEnd)
            if (name.isNotEmpty() && !name.endsWith("/")) {
                result += Entry(name, method, compressedSize.toInt(), uncompressedSize.toInt(), localOffset.toInt())
            }
            offset = nameEnd + extraLen + commentLen
        }
        return result
    }

    private fun findEocd(): Int {
        if (bytes.size < 22) throw GtfsArchiveException("not a zip archive")
        val start = bytes.size - 22
        val stop = maxOf(start - 65536, 0)
        for (i in start downTo stop) {
            if (i + 4 <= bytes.size && u32(i) == EOCD) return i
        }
        throw GtfsArchiveException("not a zip archive: end of central directory not found")
    }

    // ---- little-endian reads -------------------------------------------

    private fun u16(offset: Int): Int {
        if (offset < 0 || offset + 2 > bytes.size) throw GtfsArchiveException("corrupt ZIP offset $offset")
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(offset: Int): Long {
        if (offset < 0 || offset + 4 > bytes.size) throw GtfsArchiveException("corrupt ZIP offset $offset")
        return ((bytes[offset].toLong() and 0xFF)) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }

    // ---- inflate ---------------------------------------------------------

    private class BitReader(private val bytes: ByteArray, offset: Int, private val end: Int) {
        private var bytePos = offset
        private var bitBuffer = 0
        private var bitCount = 0

        fun nextBits(count: Int): Int {
            while (bitCount < count) {
                if (bytePos >= end) throw IllegalArgumentException("corrupt truncated DEFLATE stream")
                // `and 0xFF` before shifting: a plain `toInt()` sign-extends
                // bytes >= 0x80, and the OR accumulator would keep those 1s
                // in the buffer for every later bit in the stream.
                bitBuffer = bitBuffer or ((bytes[bytePos].toInt() and 0xFF) shl bitCount)
                bytePos++
                bitCount += 8
            }
            val value = bitBuffer and ((1 shl count) - 1)
            bitBuffer = bitBuffer ushr count
            bitCount -= count
            return value
        }

        /** Skips to the next byte boundary (stored-block framing). */
        fun alignToByte() {
            val consumedMod = ((bytePos * 8 - bitCount) % 8 + 8) % 8
            if (consumedMod != 0) {
                val drop = 8 - consumedMod
                bitBuffer = bitBuffer ushr drop
                bitCount -= drop
            }
        }
    }

    /** Canonical Huffman code: sorted symbols + offsets decode (RFC 1951 App. C). */
    private class HuffmanTable(lengths: IntArray) {
        private val counts = IntArray(16)
        private val symbols: IntArray

        init {
            for (length in lengths) {
                if (length in 1..15) counts[length]++
            }
            var left = 1
            for (len in 1..15) {
                left = left shl 1
                left -= counts[len]
                if (left < 0) throw IllegalArgumentException("corrupt oversubscribed huffman code")
            }
            val offsets = IntArray(16)
            for (len in 1 until 15) offsets[len + 1] = offsets[len] + counts[len]
            symbols = IntArray(lengths.size)
            for (i in lengths.indices) {
                val length = lengths[i]
                if (length != 0) symbols[offsets[length]++] = i
            }
        }

        fun decode(reader: BitReader): Int {
            var code = 0
            var first = 0
            var index = 0
            for (len in 1..15) {
                code = (code shl 1) or reader.nextBits(1)
                val count = counts[len]
                if (code - count < first) return symbols[index + (code - first)]
                index += count
                first = (first + count) shl 1
            }
            throw IllegalArgumentException("corrupt invalid huffman code")
        }
    }

    private fun inflate(zipBytes: ByteArray, offset: Int, compressedSize: Int, expectedSize: Int, name: String): ByteArray {
        val reader = BitReader(zipBytes, offset, offset + compressedSize)
        val out = GrowableBytes((expectedSize + 1).coerceIn(1024, 1 shl 22))
        var final = false
        while (!final) {
            final = reader.nextBits(1) == 1
            when (val type = reader.nextBits(2)) {
                BLOCK_STORED -> {
                    reader.alignToByte()
                    val len = reader.nextBits(16)
                    val nlen = reader.nextBits(16)
                    if ((len xor nlen) != 0xFFFF) throw IllegalArgumentException("corrupt stored block")
                    repeat(len) { out.writeByte(reader.nextBits(8)) }
                }
                BLOCK_FIXED -> decodeWith(reader, fixedLitTable, fixedDistTable, out, expectedSize)
                BLOCK_DYNAMIC -> {
                    val (litTable, distTable) = readDynamicTables(reader)
                    decodeWith(reader, litTable, distTable, out, expectedSize)
                }
                else -> throw IllegalArgumentException("corrupt reserved DEFLATE block type $type")
            }
            if (out.size > expectedSize) {
                throw GtfsArchiveException("corrupt ZIP entry '$name': inflated size exceeds header size")
            }
        }
        return out.toByteArray()
    }

    private fun readDynamicTables(reader: BitReader): Pair<HuffmanTable, HuffmanTable> {
        val hlit = reader.nextBits(5) + 257
        val hdist = reader.nextBits(5) + 1
        val hclen = reader.nextBits(4) + 4
        val codeLengths = IntArray(CLEN_CODES)
        for (i in 0 until hclen) codeLengths[CLEN_ORDER[i]] = reader.nextBits(3)
        if (hclen == 0) throw IllegalArgumentException("corrupt dynamic huffman lengths")
        val codeTable = HuffmanTable(codeLengths)
        val lengths = IntArray(hlit + hdist)
        var i = 0
        while (i < lengths.size) {
            when (val symbol = codeTable.decode(reader)) {
                in 0..15 -> lengths[i++] = symbol
                16 -> {
                    if (i == 0) throw IllegalArgumentException("corrupt huffman repeat without previous")
                    val previous = lengths[i - 1]
                    val repeat = reader.nextBits(2) + 3
                    repeat(repeat) {
                        if (i >= lengths.size) throw IllegalArgumentException("corrupt huffman length repeat")
                        lengths[i++] = previous
                    }
                }
                17 -> {
                    val repeat = reader.nextBits(3) + 3
                    repeat(repeat) {
                        if (i >= lengths.size) throw IllegalArgumentException("corrupt huffman length repeat")
                        lengths[i++] = 0
                    }
                }
                18 -> {
                    val repeat = reader.nextBits(7) + 11
                    repeat(repeat) {
                        if (i >= lengths.size) throw IllegalArgumentException("corrupt huffman length repeat")
                        lengths[i++] = 0
                    }
                }
                else -> throw IllegalArgumentException("corrupt huffman length symbol $symbol")
            }
        }
        return HuffmanTable(lengths.copyOfRange(0, hlit)) to HuffmanTable(lengths.copyOfRange(hlit, lengths.size))
    }

    private fun decodeWith(
        reader: BitReader,
        litTable: HuffmanTable,
        distTable: HuffmanTable,
        out: GrowableBytes,
        expectedSize: Int,
    ) {
        while (true) {
            if (out.size > expectedSize) {
                throw IllegalArgumentException("corrupt inflated size exceeds header size")
            }
            val symbol = litTable.decode(reader)
            when {
                symbol < 256 -> out.writeByte(symbol)
                symbol == END_OF_BLOCK -> return
                else -> {
                    val index = symbol - 257
                    if (index >= LEN_BASE.size) throw IllegalArgumentException("corrupt length symbol $symbol")
                    val length = LEN_BASE[index] + reader.nextBits(LEN_EXTRA[index])
                    val distSymbol = distTable.decode(reader)
                    if (distSymbol >= DIST_BASE.size) throw IllegalArgumentException("corrupt distance symbol $distSymbol")
                    val distance = DIST_BASE[distSymbol] + reader.nextBits(DIST_EXTRA[distSymbol])
                    if (distance > out.size) throw IllegalArgumentException("corrupt distance beyond output start")
                    repeat(length) {
                        out.writeByte(out.byteAt(out.size - distance))
                    }
                }
            }
        }
    }

    companion object {
        private const val LOCAL_HEADER = 0x04034b50L
        private const val CENTRAL_HEADER = 0x02014b50L
        private const val EOCD = 0x06054b50L
        private const val FLAG_ENCRYPTED = 1
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8

        private const val BLOCK_STORED = 0
        private const val BLOCK_FIXED = 1
        private const val BLOCK_DYNAMIC = 2
        private const val END_OF_BLOCK = 256
        private const val CLEN_CODES = 19
        private val CLEN_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

        private val LEN_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
        private val LEN_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
        private val DIST_BASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
        private val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)

        private val fixedLitTable = HuffmanTable(IntArray(288) { symbol ->
            when (symbol) {
                in 0..143 -> 8
                in 144..255 -> 9
                in 256..279 -> 7
                else -> 8
            }
        })
        private val fixedDistTable = HuffmanTable(IntArray(30) { 5 })
    }
}
