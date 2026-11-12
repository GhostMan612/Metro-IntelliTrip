package com.intellitrip.gtfsrt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Known-bytes test for the portable decoder.
 *
 * The hex fixture was produced by an independent protobuf encoder from the
 * official `gtfs-realtime.proto` field numbers. It covers a vehicle position,
 * an alert and a trip update in one feed, so a field-number mistake in any
 * branch fails here on both JVM and wasmJs. The JVM-only parity test compares
 * the same decoder against the generated Java bindings.
 */
class PortableFeedDecoderTest {

    private val fixture = bytes(
        "0a0b0a03322e301880f09dc70612560a0476702d31224e0a240a073130303532" +
            "3239120832353a31303a30301a0832303236313030352a033930313001420a" +
            "0a086275732d3432343212140d4b923342157465bac21d0000b4422d000038" +
            "412880f09dc70612540a07616c6572742d312a492a0912033930312a023334" +
            "52270a250a1f426c7565204c696e653a206275736573207265706c61636520" +
            "747261696e731202656e5a110a0f0a0d55736520726f757465203930327002" +
            "124f0a0474752d311a470a240a0731303035323239120832353a31303a3030" +
            "1a0832303236313030352a033930313001120d120208781a03089601220233" +
            "341a0a0a086275732d343234322080f09dc706",
    )

    @Test
    fun decodesEverySupportedEntityFromKnownBytes() {
        val feed = PortableFeedDecoder.decode(fixture)
        assertEquals(1_760_000_000L, feed.headerTimestamp)
        assertEquals(listOf("vp-1", "alert-1", "tu-1"), feed.entities.map { it.id })

        val vehicle = feed.entities[0].vehiclePosition!!
        assertEquals("1005229", vehicle.trip?.tripId)
        assertEquals("901", vehicle.trip?.routeId)
        assertEquals(1, vehicle.trip?.directionId)
        assertEquals("20261005", vehicle.trip?.startDate)
        assertEquals("25:10:00", vehicle.trip?.startTime)
        assertEquals("bus-4242", vehicle.vehicle?.id)
        assertEquals(44.892864, vehicle.position!!.latitude!!, 0.0001)
        assertEquals(-93.19815, vehicle.position!!.longitude!!, 0.0001)
        assertEquals(90.0, vehicle.position!!.bearing, 0.0001)
        assertEquals(11.5, vehicle.position!!.speed, 0.0001)
        assertEquals(1_760_000_000L, vehicle.timestamp)
        assertNull(feed.entities[0].alert)
        assertNull(feed.entities[0].tripUpdate)

        val alert = feed.entities[1].alert!!
        assertEquals(
            listOf("Blue Line: buses replace trains"),
            alert.headerText.translations,
        )
        assertEquals(listOf("Use route 902"), alert.descriptionText.translations)
        assertEquals(2, alert.severityLevelNumber)
        assertEquals(1, alert.informedEntities.size)
        assertEquals("901", alert.informedEntities.single().routeId)
        assertEquals("34", alert.informedEntities.single().stopId)

        val update = feed.entities[2].tripUpdate!!
        assertEquals("1005229", update.trip?.tripId)
        assertEquals("bus-4242", update.vehicle?.id)
        assertEquals(1_760_000_000L, update.timestamp)
        val stopUpdate = update.stopTimeUpdates.single()
        assertEquals("34", stopUpdate.stopId)
        assertEquals(120, stopUpdate.arrival?.delay)
        assertEquals(150, stopUpdate.departure?.delay)
    }

    @Test
    fun emptyPayloadIsMalformedNotAnEmptyFeed() {
        assertFailsWith<GtfsRealtimeException> { PortableFeedDecoder.decode(ByteArray(0)) }
    }

    @Test
    fun missingRequiredHeaderVersionIsMalformed() {
        // FeedMessage.header { timestamp = 1760000000 } with no version.
        val noVersion = bytes("0a061880f09dc706")
        assertFailsWith<GtfsRealtimeException> { PortableFeedDecoder.decode(noVersion) }
    }

    @Test
    fun truncatedLengthIsMalformed() {
        val truncated = bytes("0a0b0a03322e301880f09dc706")
        assertFailsWith<GtfsRealtimeException> {
            PortableFeedDecoder.decode(truncated.copyOf(truncated.size - 4))
        }
    }

    @Test
    fun invalidUtf8InAnEntityIdIsMalformed() {
        val header = "0a0b0a03322e301880f09dc706"
        // entity { id = 0xFF 0xFF 0xFF }
        val badEntity = "12050a03ffffff"
        assertFailsWith<GtfsRealtimeException> {
            PortableFeedDecoder.decode(bytes(header + badEntity))
        }
    }

    @Test
    fun unsupportedGroupWireTypeIsMalformed() {
        val header = "0a0b0a03322e301880f09dc706"
        assertFailsWith<GtfsRealtimeException> {
            // Field 1 with wire type 3 (start group): GTFS-Realtime does not
            // use groups, so this cannot be a legitimate extension.
            PortableFeedDecoder.decode(bytes(header + "0b"))
        }
    }

    @Test
    fun overlongVarintIsMalformed() {
        val header = "0a0b0a03322e301880f09dc706"
        // Unknown field 3, wire type 0, with eleven continuation bytes.
        assertFailsWith<GtfsRealtimeException> {
            PortableFeedDecoder.decode(bytes(header + "18" + "80".repeat(10)))
        }
    }

    @Test
    fun unknownFieldsAreSkippedWithoutLosingTheFeed() {
        val header = "0a0b0a03322e301880f09dc706"
        // Unknown field 4, varint 7.
        val feed = PortableFeedDecoder.decode(bytes(header + "2007"))
        assertEquals(1_760_000_000L, feed.headerTimestamp)
        assertTrue(feed.entities.isEmpty())
    }

    @Test
    fun platformDecoderIsThePortableOneOnWasmAndMatchesOnJvm() {
        // Common test runs on JVM and wasmJs. On wasmJs this is the portable
        // decoder; on JVM it is the Java-binding actual, which the parity test
        // separately proves equal for the same bytes.
        val feed = platformFeedDecoder().decode(fixture)
        assertEquals(listOf("vp-1", "alert-1", "tu-1"), feed.entities.map { it.id })
    }

    private fun bytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex length must be even" }
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
