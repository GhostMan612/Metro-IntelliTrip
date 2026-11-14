package com.intellitrip.gtfs

/**
 * Small big-endian binary helpers for format code that used to stream through
 * platform data streams. All integers are big-endian and strings use the
 * length-prefixed modified UTF-8 wire format of the platform data-stream
 * writer, so files written by the portable code stay byte-identical to the
 * ones the old JVM code produced.
 *
 * Everything here is pure common code: no platform streams required.
 */

/** Grow-on-demand byte buffer with primitive writers. */
internal class GrowableBytes(private var capacity: Int = 1024) {
    private var storage = ByteArray(capacity)
    var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > storage.size) {
            val grown = (storage.size * 2).coerceAtLeast(size + extra).coerceAtLeast(16)
            storage = storage.copyOf(grown)
        }
    }

    fun writeByte(value: Int) {
        ensure(1)
        storage[size++] = (value and 0xFF).toByte()
    }

    fun writeBytes(bytes: ByteArray) {
        ensure(bytes.size)
        bytes.copyInto(storage, size)
        size += bytes.size
    }

    fun writeBytes(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "invalid byte slice ($offset, $length) for size ${bytes.size}"
        }
        ensure(length)
        bytes.copyInto(storage, size, startIndex = offset, endIndex = offset + length)
        size += length
    }

    fun writeShort(value: Int) {
        ensure(2)
        storage[size++] = (value ushr 8).toByte()
        storage[size++] = value.toByte()
    }

    fun writeInt(value: Int) {
        ensure(4)
        storage[size++] = (value ushr 24).toByte()
        storage[size++] = (value ushr 16).toByte()
        storage[size++] = (value ushr 8).toByte()
        storage[size++] = value.toByte()
    }

    fun writeLong(value: Long) {
        ensure(8)
        for (shift in 56 downTo 0 step 8) storage[size++] = (value ushr shift).toByte()
    }

    fun writeDouble(value: Double) = writeLong(value.toBits())

    fun writeBoolean(value: Boolean) = writeByte(if (value) 1 else 0)

    /** Value of the byte at [index], for DEFLATE back-references. */
    fun byteAt(index: Int): Int = storage[index].toInt() and 0xFF

    /**
     * Modified-UTF-8 writer matching the platform `writeUTF`: supplementary
     * characters are emitted as two 3-byte surrogates and NUL as `C0 80`.
     */
    fun writeUtf8String(value: String) {
        val encoded = encodeModifiedUtf8(value)
        // The 2-byte length matches the old platform writer, which failed for
        // spans past 64 KiB; fail here the same way rather than truncating.
        require(encoded.size <= 0xFFFF) { "string too large for binary cache" }
        writeShort(encoded.size)
        writeBytes(encoded)
    }

    fun toByteArray(): ByteArray = storage.copyOf(size)
}

/** Forward reader over a byte array with big-endian primitive reads. */
internal class ByteArrayCursor(private val bytes: ByteArray, start: Int = 0) {
    var position: Int = start
        private set
    val size: Int get() = bytes.size
    val remaining: Int get() = bytes.size - position

    fun readByte(): Int {
        need(1)
        return bytes[position++].toInt() and 0xFF
    }

    fun readShort(): Int {
        need(2)
        val value = ((bytes[position].toInt() and 0xFF) shl 8) or (bytes[position + 1].toInt() and 0xFF)
        position += 2
        return value
    }

    fun readInt(): Int {
        need(4)
        val value = ((bytes[position].toInt() and 0xFF) shl 24) or
            ((bytes[position + 1].toInt() and 0xFF) shl 16) or
            ((bytes[position + 2].toInt() and 0xFF) shl 8) or
            (bytes[position + 3].toInt() and 0xFF)
        position += 4
        return value
    }

    fun readLong(): Long {
        need(8)
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (bytes[position + i].toLong() and 0xFF)
        position += 8
        return value
    }

    fun readDouble(): Double = Double.fromBits(readLong())

    fun readBoolean(): Boolean = when (readByte()) {
        1 -> true
        0 -> false
        else -> throw IllegalArgumentException("corrupt boolean")
    }

    fun readBytes(count: Int): ByteArray {
        need(count)
        val out = bytes.copyOfRange(position, position + count)
        position += count
        return out
    }

    fun skip(count: Int) {
        need(count)
        position += count
    }

    /** Modified-UTF-8 reader matching [GrowableBytes.writeUtf8String]. */
    fun readUtf8String(): String {
        val length = readShort()
        val start = position
        val value = decodeModifiedUtf8(bytes, start, length)
        position = start + length
        return value
    }

    private fun need(count: Int) {
        if (count < 0 || position + count > bytes.size) {
            throw IllegalArgumentException("corrupt end of data at $position (need $count)")
        }
    }
}

/** Encodes [value] as modified UTF-8 (see [GrowableBytes.writeUtf8String]). */
internal fun encodeModifiedUtf8(value: String): ByteArray {
    val out = GrowableBytes(value.length * 3 + 1)
    for (c in value) {
        val code = c.code
        when {
            code in 0x01..0x7F -> out.writeByte(code)
            code == 0 || code in 0x80..0x7FF -> {
                // NUL is two bytes (`C0 80`); 0x80..0x7FF is two bytes.
                out.writeByte(0xC0 or (code ushr 6))
                out.writeByte(0x80 or (code and 0x3F))
            }
            else -> {
                out.writeByte(0xE0 or (code ushr 12))
                out.writeByte(0x80 or ((code ushr 6) and 0x3F))
                out.writeByte(0x80 or (code and 0x3F))
            }
        }
    }
    return out.toByteArray()
}

/** Decodes a modified-UTF-8 span (the reader form of [encodeModifiedUtf8]). */
internal fun decodeModifiedUtf8(bytes: ByteArray, offset: Int, length: Int): String {
    if (offset < 0 || length < 0 || offset + length > bytes.size) {
        throw IllegalArgumentException("corrupt modified UTF-8 span")
    }
    val builder = StringBuilder(length)
    var i = offset
    val end = offset + length
    while (i < end) {
        val first = bytes[i].toInt() and 0xFF
        when {
            first < 0x80 -> {
                builder.append(first.toChar())
                i += 1
            }
            first and 0xE0 == 0xC0 -> {
                if (i + 1 >= end) throw IllegalArgumentException("corrupt modified UTF-8")
                val second = bytes[i + 1].toInt() and 0xFF
                builder.append((((first and 0x1F) shl 6) or (second and 0x3F)).toChar())
                i += 2
            }
            first and 0xF0 == 0xE0 -> {
                if (i + 2 >= end) throw IllegalArgumentException("corrupt modified UTF-8")
                val second = bytes[i + 1].toInt() and 0xFF
                val third = bytes[i + 2].toInt() and 0xFF
                builder.append((((first and 0x0F) shl 12) or ((second and 0x3F) shl 6) or (third and 0x3F)).toChar())
                i += 3
            }
            else -> throw IllegalArgumentException("corrupt modified UTF-8")
        }
    }
    return builder.toString()
}
