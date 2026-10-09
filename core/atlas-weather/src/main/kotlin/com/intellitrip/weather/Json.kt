package com.intellitrip.weather

/**
 * Tiny dependency-free JSON reader for provider payloads. Weather providers
 * return small, well-formed documents, so a narrow reader avoids pulling a JSON
 * dependency into the portable core.
 */
internal object Json {

    fun parse(text: String): Any? = Reader(text).readValue()

    private class Reader(private val text: String) {
        private var index = 0

        fun readValue(): Any? {
            skipWhitespace()
            if (index >= text.length) return null
            return when (text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { index++; return result }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                result[key] = readValue()
                skipWhitespace()
                when (val c = next()) {
                    ',' -> Unit
                    '}' -> return result
                    else -> error("Unexpected '$c' in object")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val result = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') { index++; return result }
            while (true) {
                result += readValue()
                skipWhitespace()
                when (val c = next()) {
                    ',' -> Unit
                    ']' -> return result
                    else -> error("Unexpected '$c' in array")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                when (val c = next()) {
                    '"' -> return builder.toString()
                    '\\' -> when (val escaped = next()) {
                        'n' -> builder.append('\n')
                        't' -> builder.append('\t')
                        'r' -> builder.append('\r')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('')
                        'u' -> {
                            val hex = text.substring(index, index + 4)
                            index += 4
                            builder.append(hex.toInt(16).toChar())
                        }

                        else -> builder.append(escaped)
                    }

                    else -> builder.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = index
            while (index < text.length && (text[index].isDigit() || text[index] in "-+.eE")) index++
            val raw = text.substring(start, index)
            return if (raw.contains('.') || raw.contains('e', true)) {
                raw.toDouble()
            } else {
                raw.toLong()
            }
        }

        private fun <T> readLiteral(literal: String, value: T): T {
            require(text.startsWith(literal, index)) { "Invalid JSON literal at $index" }
            index += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun peek(): Char? = text.getOrNull(index)

        private fun next(): Char = text[index++]

        private fun expect(expected: Char) {
            skipWhitespace()
            val actual = next()
            require(actual == expected) { "Expected '$expected' but found '$actual'" }
        }
    }
}

internal fun Any?.asObject(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()

internal fun Any?.asList(): List<Any?> = this as? List<Any?> ?: emptyList()

internal fun Any?.asStringOrNull(): String? = this as? String

internal fun Any?.asDoubleOrNull(): Double? = when (this) {
    is Double -> this
    is Long -> this.toDouble()
    is Int -> this.toDouble()
    else -> null
}

internal fun Any?.asLongOrNull(): Long? = when (this) {
    is Long -> this
    is Int -> this.toLong()
    is Double -> this.toLong()
    else -> null
}