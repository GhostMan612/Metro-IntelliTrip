package com.intellitrip.routing

/**
 * Minimal JSON reader for provider responses.
 *
 * Hand-rolled rather than pulling in a JSON library: `atlas-routing` is a
 * published SDK module, and adding a serialization plugin or a transitive
 * dependency there would impose it on every consumer. The subset needed to read
 * an OpenTripPlanner plan response is small enough to own.
 *
 * Maps JSON null to `null` and distinguishes it from an absent key by wrapping
 * values in [JsonValue], so "field missing" and "field is null" stay
 * distinguishable — provider responses rely on that distinction.
 */
sealed class JsonValue {
    data object Null : JsonValue()

    data class Bool(val value: Boolean) : JsonValue()

    data class Num(val value: Double) : JsonValue()

    data class Str(val value: String) : JsonValue()

    data class Arr(val items: List<JsonValue>) : JsonValue()

    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()

    /** Field lookup that tolerates absence; used everywhere below for optionals. */
    operator fun get(key: String): JsonValue? = (this as? Obj)?.fields?.get(key)

    fun string(key: String): String? = (get(key) as? Str)?.value

    fun number(key: String): Double? = (get(key) as? Num)?.value

    fun long(key: String): Long? = number(key)?.toLong()

    fun bool(key: String): Boolean? = (get(key) as? Bool)?.value

    fun array(key: String): List<JsonValue> = (get(key) as? Arr)?.items ?: emptyList()

    fun obj(key: String): JsonValue? = get(key) as? Obj

    companion object {
        fun parse(text: String): JsonValue {
            val reader = Reader(text)
            val value = reader.readValue()
            reader.skipWhitespace()
            return value
        }
    }

    private class Reader(private val text: String) {
        private var pos = 0

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun readValue(): JsonValue {
            skipWhitespace()
            require(pos < text.length) { "unexpected end of JSON" }
            return when (text[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> Str(readString())
                't' -> { expect("true"); Bool(true) }
                'f' -> { expect("false"); Bool(false) }
                'n' -> { expect("null"); Null }
                else -> readNumber()
            }
        }

        private fun expect(literal: String) {
            require(text.startsWith(literal, pos)) { "expected $literal at $pos" }
            pos += literal.length
        }

        private fun readObject(): JsonValue {
            pos++ // consume '{'
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (pos < text.length && text[pos] == '}') {
                pos++
                return Obj(fields)
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                require(pos < text.length && text[pos] == ':') { "expected ':' at $pos" }
                pos++
                fields[key] = readValue()
                skipWhitespace()
                require(pos < text.length) { "unterminated object" }
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return Obj(fields) }
                    else -> error("expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(): JsonValue {
            pos++ // consume '['
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (pos < text.length && text[pos] == ']') {
                pos++
                return Arr(items)
            }
            while (true) {
                items += readValue()
                skipWhitespace()
                require(pos < text.length) { "unterminated array" }
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return Arr(items) }
                    else -> error("expected ',' or ']' at $pos")
                }
            }
        }

        private fun readString(): String {
            require(pos < text.length && text[pos] == '"') { "expected string at $pos" }
            pos++
            val out = StringBuilder()
            while (pos < text.length) {
                when (val c = text[pos]) {
                    '"' -> { pos++; return out.toString() }
                    '\\' -> {
                        pos++
                        require(pos < text.length) { "dangling escape" }
                        when (val esc = text[pos]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append(12.toChar())
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                val hex = text.substring(pos + 1, pos + 5)
                                out.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> error("unknown escape \\$esc at $pos")
                        }
                        pos++
                    }
                    else -> { out.append(c); pos++ }
                }
            }
            error("unterminated string")
        }

        private fun readNumber(): JsonValue {
            val start = pos
            if (pos < text.length && (text[pos] == '-' || text[pos] == '+')) pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            val slice = text.substring(start, pos)
            val value = slice.toDoubleOrNull() ?: error("malformed number '$slice' at $start")
            return Num(value)
        }
    }
}