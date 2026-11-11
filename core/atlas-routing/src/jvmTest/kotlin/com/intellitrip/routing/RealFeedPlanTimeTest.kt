package com.intellitrip.routing

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.TripPlanRequest
import com.intellitrip.gtfs.GtfsArchive
import com.intellitrip.gtfs.GtfsStaticParser
import com.intellitrip.gtfs.StopTimeIndexReader
import com.intellitrip.gtfs.StopTimeIndexWriter
import com.intellitrip.gtfs.StopTimeSink
import java.nio.file.Files
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Plans the real Metro Transit feed through the disk stop-time index, which is the
 * path the app actually uses. The in-memory path is covered elsewhere; if the index
 * or its reverse lookup is wrong, the app plans nothing and says so as "0 journeys",
 * which is easy to miss without this test.
 *
 * Skipped when the fixture is absent. Fetch it with:
 *   curl -o gtfs-metro-transit.zip https://svc.metrotransit.org/mtgtfs/gtfs.zip
 */
class RealFeedPlanTimeTest {

    private fun fixturePath(): java.io.File? {
        val override = System.getProperty("intellitrip.gtfs.fixture")
        val candidates = listOfNotNull(
            override?.let { java.io.File(it) },
            java.io.File("../gtfs-metro-transit.zip"),
        )
        return candidates.firstOrNull { it.exists() }
    }

    private fun parseFeed(file: java.io.File, stopTimeSink: StopTimeSink?): com.intellitrip.gtfs.StaticFeed {
        val feedId = FeedId("metro-transit-regional")
        val payload = file.readBytes()
        val files = GtfsArchive.read(payload).toMutableMap()
        val metadata = FeedMetadata(feedId, null, null, null, null, null, Clock.System.now(), null, null, null)
        return GtfsStaticParser.parse(feedId, files, metadata, stopTimeSink)
    }

    @Test
    fun plansOnRealFeedWithinLatencyBudget() {
        val file = fixturePath() ?: run {
            if (System.getenv("CI") == "true" || System.getProperty("intellitrip.gtfs.fixture") != null) {
                kotlin.test.fail("Real-feed CI gate requires an accessible GTFS fixture; none was found")
            }
            println("SKIPPED optional local real-feed plan timing: no gtfs-metro-transit.zip fixture present")
            return
        }

        // Bytes-based index API (KMP): writer finishes to a pair of byte
        // images; the reader parses from bytes without a platform channel.
        val writer = StopTimeIndexWriter()
        val feedThroughSink = parseFeed(file, StopTimeSink { writer.write(it) })
        check(feedThroughSink.stopTimes.isEmpty()) { "sink path should not retain stop times in memory" }
        val (dataBytes, indexBytes) = writer.finish()

        val feed = parseFeed(file, StopTimeSink { })
        run {
            val reader = StopTimeIndexReader(indexBytes, dataBytes)
            assertTrue(reader.tripCount() > 1_000, "index holds ${reader.tripCount()} trips")
            assertTrue(reader.rowCount() > 10_000, "index holds ${reader.rowCount()} rows")

            val source = object : StopTimeSource {
                override fun stopTimesFor(trip: com.intellitrip.domain.TripKey) =
                    reader.stopTimesFor(trip) { stopId -> StopKey(feed.feedId, stopId) }

                override fun tripsServing(stop: StopKey) = reader.tripsServing(stop)
            }

            val network = RoutingNetwork(
                feedId = feed.feedId,
                metadata = feed.metadata,
                routes = feed.routes,
                stops = feed.stops,
                trips = feed.trips,
                stopTimes = emptyList(),
                stopTimeSource = source,
                transfers = feed.transfers,
            )

            // Diagnose rather than guess: an empty result here means the index lookup
            // or the reverse lookup is empty, not that the planner is wrong.
            val sampleOrigin = feed.stops.minByOrNull {
                com.intellitrip.routing.haversineMeters(GeoPoint(44.9765, -93.2650), it.location)
            }!!
            val serving = network.tripsServing(sampleOrigin.key)
            println("origin stop ${sampleOrigin.key}, ${serving.size} trips serve it")
            val firstTimes = serving.firstOrNull()?.let { network.stopTimesFor(it.key) } ?: emptyList()
            println("first trip has ${firstTimes.size} stop times, first=${firstTimes.firstOrNull()?.stopKey}")
            check(serving.isNotEmpty()) { "reverse index returned no trips for ${sampleOrigin.key}" }
            check(firstTimes.isNotEmpty()) { "stop-time index returned no rows for ${serving.first().key}" }

// The exact pair and transfer budget the app uses by default (AtlasHost.DEFAULT_LAT
            // and AppState.destination), so this fails if the app itself stops planning.
            // Midday rather than "now": late at night there is no service left in the
            // window, which would make the test pass or fail by time of day.
            val zone = TimeZone.of("America/Chicago")
            val today = Clock.System.now().toLocalDateTime(zone).date
            val midday = LocalDateTime(today.year, today.monthNumber, today.dayOfMonth, 12, 0)
                .toInstant(zone)
            // The app's own default pair, so this covers what the user actually sees.
            val request = TripPlanRequest(
                origin = GeoPoint(44.9765, -93.2650),
                destination = GeoPoint(44.9580, -93.1560),
                departAt = midday,
                arriveBy = null,
                maxTransfers = 2,
            )

            val planner = StaticNetworkPlanner(network)

            fun planJourneys(departAt: Instant?): Int =
                (kotlinx.coroutines.runBlocking { planner.plan(request.copy(departAt = departAt)) }
                    as? com.intellitrip.contracts.ProviderResult.Success)?.data?.size ?: 0

            val middayJourneys = planJourneys(midday)
            val nowJourneys = planJourneys(Clock.System.now())
            println("midday=$middayJourneys journeys, now=$nowJourneys")
            // At 23:00 "no journeys" is the correct answer, so only the midday figure is
            // load-bearing. Asserting on "now" would make this test clock-dependent.
            assertTrue(middayJourneys > 0, "disk-index plan found no journeys at midday")

            repeat(2) { attempt ->
                val started = System.nanoTime()
                val result = kotlinx.coroutines.runBlocking { planner.plan(request) }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                val success = result as? com.intellitrip.contracts.ProviderResult.Success
                println("plan #$attempt took ${elapsedMs}ms, ${success?.data?.size ?: 0} journeys")

                assertTrue(success != null, "expected a complete result, got ${result::class.simpleName}")
                // Room for a slow emulator or cold JVM; observed is roughly 1.5-3.5s,
                // against over a minute before the search was bounded.
                assertTrue(elapsedMs < 20_000, "planning took ${elapsedMs}ms, expected under 20s")
                assertTrue(success.data.isNotEmpty(), "disk-index plan found no journeys at midday")
            }
        }
    }
}