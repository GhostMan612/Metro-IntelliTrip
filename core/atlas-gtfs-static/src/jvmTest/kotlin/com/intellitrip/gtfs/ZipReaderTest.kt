package com.intellitrip.gtfs

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * JVM-only coverage for the portable ZIP/DEFLATE reader: these zips are
 * built with the platform ZIP writer (DEFLATED entries, data descriptors,
 * real local headers), so every decode path of [GtfsArchive] is verified
 * against known content.
 */
class ZipReaderTest {

    private fun zipBytes(vararg entries: Triple<String, ByteArray, Int?>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, data, method) ->
                val entry = ZipEntry(name)
                if (method != null) {
                    entry.method = method
                    entry.size = data.size.toLong()
                    entry.crc = CRC32().apply { update(data) }.value
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun decodesDeflatedNestedAndStoredEntries() {
        val csv = "stop_id,stop_name\n" + "BESK,Station\n".repeat(2000) + "x,last\n"
        val big = "trip_id,stop_id\n".plus("1,S\n".repeat(50_000))
        val zip = zipBytes(
            Triple("a/b/routes.txt", csv.encodeToByteArray(), null),
            Triple("a/", ByteArray(0), null),
            Triple("big.txt", big.encodeToByteArray(), null),
            Triple("stored.txt", "stored contents\n".encodeToByteArray(), ZipEntry.STORED),
            Triple("empty.txt", ByteArray(0), null),
        )

        val files = GtfsArchive.read(zip)
        assertEquals(csv.encodeToByteArray().toList(), files["routes.txt"]!!.toList())
        assertEquals(big.encodeToByteArray().toList(), files["big.txt"]!!.toList())
        assertEquals("stored contents\n".encodeToByteArray().toList(), files["stored.txt"]!!.toList())
        assertEquals(emptyList<Byte>(), files["empty.txt"]!!.toList())
        // Directory entries must not appear as tables.
        assertTrue("a/" !in files)
    }

    @Test
    fun rejectsNonZip() {
        assertTrue(!GtfsArchive.isZip("not a zip".encodeToByteArray()))
        assertFailsWith<GtfsArchiveException> { GtfsArchive.read("not a zip".encodeToByteArray()) }
        assertFailsWith<GtfsArchiveException> { GtfsArchive.read(ByteArray(0)) }
    }

    @Test
    fun decodesVariedTextAtMaxCompression() {
        // Mixed-frequency content at the highest compression level: this is
        // what produces dynamic-Huffman blocks in the old platform reads.
        val text = buildString {
            repeat(1200) { i ->
                append("trip ").append(i).append(" route 901 stop ")
                append("abcdefghijklmnopqrstuvwxyz".take(1 + (i * 7) % 26)).append(" status 0|1 dt=4\n")
            }
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(9)
            zip.putNextEntry(ZipEntry("varied.txt"))
            zip.write(text.encodeToByteArray())
            zip.closeEntry()
        }
        val files = GtfsArchive.read(out.toByteArray())
        assertEquals(text.encodeToByteArray().toList(), files["varied.txt"]!!.toList())
    }
}
