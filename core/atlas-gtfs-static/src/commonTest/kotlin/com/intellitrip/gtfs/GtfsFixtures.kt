package com.intellitrip.gtfs

/**
 * Shared minimal-feed fixture for pure (ByteArray) tests.
 *
 * [zipOf] is a minimal stored-only (uncompressed) ZIP writer so common tests
 * can exercise the ZIP-based parse paths without platform ZIP APIs; the
 * on-JVM DEFLATE paths are covered in `jvmTest` with real compressed zips.
 */
internal object GtfsFixtures {

    fun zipOf(files: Map<String, String>): ByteArray {
        val out = GrowableBytes(4096)
        val central = mutableListOf<CentralEntry>()
        files.forEach { (name, content) ->
            val data = content.encodeToByteArray()
            val nameBytes = name.encodeToByteArray()
            val crc = crc32(data)
            val offset = out.size
            // Local header (sig 0x04034b50 ...).
            out.writeLeInt(0x04034b50)
            out.writeLeShort(20) // version needed
            out.writeLeShort(0) // flags
            out.writeLeShort(0) // method: stored
            out.writeLeShort(0) // time
            out.writeLeShort(0) // date
            out.writeLeInt(crc)
            out.writeLeInt(data.size)
            out.writeLeInt(data.size)
            out.writeLeShort(nameBytes.size)
            out.writeLeShort(0) // extra len
            out.writeBytes(nameBytes)
            out.writeBytes(data)
            central += CentralEntry(nameBytes, crc, data.size, offset)
        }
        val centralOffset = out.size
        central.forEach { entry ->
            out.writeLeInt(0x02014b50)
            out.writeLeShort(20) // version made by
            out.writeLeShort(20) // version needed
            out.writeLeShort(0) // flags
            out.writeLeShort(0) // method
            out.writeLeShort(0)
            out.writeLeShort(0)
            out.writeLeInt(entry.crc)
            out.writeLeInt(entry.size)
            out.writeLeInt(entry.size)
            out.writeLeShort(entry.name.size)
            out.writeLeShort(0) // extra len
            out.writeLeShort(0) // comment len
            out.writeLeShort(0) // disk start
            out.writeLeShort(0) // internal attrs
            out.writeLeInt(0) // external attrs
            out.writeLeInt(entry.offset)
            out.writeBytes(entry.name)
        }
        val centralSize = out.size - centralOffset
        // End of central directory (sig 0x06054b50 ...).
        out.writeLeInt(0x06054b50)
        out.writeLeShort(0)
        out.writeLeShort(0)
        out.writeLeShort(central.size)
        out.writeLeShort(central.size)
        out.writeLeInt(centralSize)
        out.writeLeInt(centralOffset)
        out.writeLeShort(0)
        return out.toByteArray()
    }

    private data class CentralEntry(val name: ByteArray, val crc: Int, val size: Int, val offset: Int)

    private val crcTable = IntArray(256) { i ->
        var c = i
        repeat(8) {
            c = if (c and 1 != 0) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1
        }
        c
    }

    /** CRC-32 (IEEE 802.3) of [bytes]. */
    private fun crc32(bytes: ByteArray): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (b in bytes) crc = (crc ushr 8) xor crcTable[(crc xor (b.toInt() and 0xFF)) and 0xFF]
        return crc xor 0xFFFFFFFF.toInt()
    }

    /**
     * ZIP is little-endian, while [GrowableBytes] is big-endian for the cache
     * and index formats, so the fixture writes little-endian ZIP fields here.
     */
    private fun GrowableBytes.writeLeShort(value: Int) {
        writeByte(value)
        writeByte(value ushr 8)
    }

    private fun GrowableBytes.writeLeInt(value: Int) {
        writeByte(value)
        writeByte(value ushr 8)
        writeByte(value ushr 16)
        writeByte(value ushr 24)
    }

    val minimalFeed: Map<String, String> = linkedMapOf(
        "agency.txt" to """
            agency_id,agency_name,agency_url,agency_timezone
            1,Metro Transit,https://www.metrotransit.org,America/Chicago
        """.trimIndent() + "\n",
        "routes.txt" to """
            route_id,agency_id,route_short_name,route_long_name,route_type
            901,1,A,Blue Line,0
        """.trimIndent() + "\n",
        "stops.txt" to """
            stop_id,stop_name,stop_lat,stop_lon
            34,Chicago Ave,44.97,-93.26
            39,17th St,44.96,-93.27
        """.trimIndent() + "\n",
        "trips.txt" to """
            route_id,service_id,trip_id,trip_headsign,direction_id,shape_id
            901,S1,1001,Northbound,0,SH1
        """.trimIndent() + "\n",
        "stop_times.txt" to """
            trip_id,arrival_time,departure_time,stop_id,stop_sequence
            1001,23:45:00,23:50:00,34,1
            1001,25:10:00,25:15:00,39,2
        """.trimIndent() + "\n",
        "calendar.txt" to """
            service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
            S1,1,1,1,1,1,0,0,20260101,20261231
        """.trimIndent() + "\n",
        "calendar_dates.txt" to """
            service_id,date,exception_type
            S1,20260704,2
        """.trimIndent() + "\n",
        "shapes.txt" to """
            shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence
            SH1,44.97,-93.26,2
            SH1,44.96,-93.27,1
        """.trimIndent() + "\n",
        "feed_info.txt" to """
            feed_publisher_name,feed_lang,feed_start_date,feed_end_date,feed_version
            Metro Transit,en,20260101,20261231,1
        """.trimIndent() + "\n",
    )
}
