package com.intellitrip.gtfsrt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Byte-for-byte parity between the generated Java bindings and the portable
 * wire decoder.
 *
 * This is the strongest check available without a second protobuf runtime:
 * official Java protobuf produces the bytes, both decoders consume them, and
 * the whole portable model must be equal. Differences in field numbers,
 * presence semantics or float handling fail here rather than on a browser.
 */
class DecoderParityTest {

    @Test
    fun vehiclePositionsMatchJavaBindings() {
        assertParity(RealtimeProtoFixtures.vehiclePositionsFeed())
    }

    @Test
    fun tripUpdatesMatchJavaBindings() {
        assertParity(RealtimeProtoFixtures.tripUpdatesFeed())
    }

    @Test
    fun alertsMatchJavaBindings() {
        assertParity(RealtimeProtoFixtures.alertsFeed())
    }

    @Test
    fun truncatedRealFeedIsRejectedByThePortableDecoder() {
        val bytes = RealtimeProtoFixtures.vehiclePositionsFeed()
        assertFailsWith<GtfsRealtimeException> {
            PortableFeedDecoder.decode(bytes.copyOf(bytes.size - 3))
        }
    }

    private fun assertParity(bytes: ByteArray) {
        val fromJavaBindings = platformFeedDecoder().decode(bytes)
        val fromPortable = PortableFeedDecoder.decode(bytes)
        assertEquals(fromJavaBindings, fromPortable)
    }
}
