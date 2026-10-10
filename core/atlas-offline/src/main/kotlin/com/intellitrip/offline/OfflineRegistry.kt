package com.intellitrip.offline

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.Vehicle
import com.intellitrip.gtfs.GtfsArchive
import com.intellitrip.gtfs.GtfsStaticParser
import com.intellitrip.gtfs.StaticFeed
import com.intellitrip.gtfs.StaticFeedSnapshotStore
import java.time.Instant
import java.nio.file.Path

/**
 * Offline capability surface.
 *
 * Static network availability is derived from the local snapshot store; realtime
 * availability additionally requires a fresh cached snapshot. When realtime data
 * is not presentable the engine reports `realtimeUsable = false` so the UI can
 * hide live positions rather than draw stale ones as if they were moving.
 */
class OfflineRegistry(
    private val store: StaticFeedSnapshotStore,
    private val staleness: StalenessModel = StalenessModel(),
) {

    data class Capabilities(
        val staticNetworkUsable: Boolean,
        val realtimeUsable: Boolean,
        val realtimeCached: Boolean,
        val realtimeFreshness: Freshness,
        val staticFreshness: Freshness,
        val basemap: BasemapAvailability,
        val routingAvailable: Boolean,
        val notes: List<String>,
    )

    fun capabilities(
        feedId: FeedId,
        realtimeEntry: CacheEntry? = null,
        basemap: BasemapAvailability = BasemapAvailability.OnlineOnly,
        now: Instant = Instant.now(),
    ): Capabilities {
        val staticEntry = store.activeSnapshot(feedId)?.metadata?.let { metadata ->
            CacheEntry(
                feedId = feedId,
                fetchedAt = metadata.fetchedAt,
                validityStart = metadata.validFrom,
                validityEnd = metadata.validTo,
                version = metadata.version,
                hash = metadata.hash,
            )
        }
        val staticFreshness = staleness.classify(staticEntry, now)
        val realtimeFreshness = staleness.classify(realtimeEntry, now)
        val staticUsable = staleness.isUsableStatic(staticEntry, now)
        val notes = buildList {
            if (staticFreshness == Freshness.ABSENT) add("No static GTFS snapshot stored for this feed")
            if (realtimeEntry != null && realtimeFreshness != Freshness.FRESH) {
                add("Cached realtime data is ${realtimeFreshness.name.lowercase()}; live positions hidden")
            }
            if (basemap == BasemapAvailability.OfflineOnlyMissing) {
                add("Offline basemap package is missing; map will show an empty canvas")
            }
        }
        return Capabilities(
            staticNetworkUsable = staticUsable,
            realtimeUsable = staleness.isPresentableAsLive(realtimeEntry, now),
            realtimeCached = realtimeEntry != null,
            realtimeFreshness = realtimeFreshness,
            staticFreshness = staticFreshness,
            basemap = basemap,
            routingAvailable = staticUsable,
            notes = notes,
        )
    }

    /**
     * Loads and parses the stored static snapshot; null when nothing is stored.
     *
     * Stop times are deliberately discarded here: they are already on disk in the
     * snapshot's stop-time index and are read back on demand by the planner. Letting
     * the parser collect them would rebuild the whole table in memory on every cold
     * start, which is what the on-disk index exists to avoid.
     */
    fun loadStaticFeed(feedId: FeedId): StaticFeed? {
        val snapshot = store.activeSnapshot(feedId) ?: return null
        val payload = runCatching { store.readPayload(feedId) }.getOrNull() ?: return null
        val files = runCatching { GtfsArchive.read(payload).toMutableMap() }.getOrNull() ?: return null
        return runCatching {
            GtfsStaticParser.parse(
                feedId,
                files,
                snapshot.metadata,
                com.intellitrip.gtfs.StopTimeSink { },
            )
        }.getOrNull()
    }

    /** Realtime positions are only handed out while they are fresh. */
    fun liveVehicles(entry: CacheEntry?, vehicles: List<Vehicle>, now: Instant = Instant.now()): List<Vehicle> =
        if (staleness.isPresentableAsLive(entry, now)) vehicles else emptyList()
}