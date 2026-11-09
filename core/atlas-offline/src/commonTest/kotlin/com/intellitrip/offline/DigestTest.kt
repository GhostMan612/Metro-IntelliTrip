package com.intellitrip.offline

import kotlin.test.Test
import kotlin.test.assertEquals

class DigestTest {

    @Test
    fun sha256MatchesKnownVectors() {
        // Standard FIPS vectors; guards the pure-Kotlin digest against typos so
        // content-addressed storage names stay stable across targets.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(ByteArray(0)),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".encodeToByteArray()),
        )
    }

    @Test
    fun sha256SpansMultipleBlocks() {
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
    }

    @Test
    fun base64RoundTripsAndMatchesJavaEncoder() {
        // "metro" -> bWV0cm8= is the standard Base64 encoding of these bytes.
        assertEquals("bWV0cm8=", base64Encode("metro".encodeToByteArray()))
        assertEquals("metro", base64Decode("bWV0cm8=").decodeToString())
        assertEquals("", base64Encode(ByteArray(0)))
        assertEquals(0, base64Decode("").size)
    }

    @Test
    fun base64RoundTripsUtf8Text() {
        val text = "Twin Cities metro"
        assertEquals(text, base64Decode(base64Encode(text.encodeToByteArray())).decodeToString())
    }
}
