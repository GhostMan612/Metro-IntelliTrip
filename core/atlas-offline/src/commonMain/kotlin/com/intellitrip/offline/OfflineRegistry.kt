package com.intellitrip.offline

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.Vehicle
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * What the offline registry needs from the static snapshot store.
 *
 * `atlas-gtfs-static` (`StaticFeedSnapshotStore`) is still kotlin.jvm, so the
 * registry cannot depend on it in commonMain. The jvmMain adapter
 * ([JvmOfflineSnapshotPort]) maps it onto this port. Once gtfs-static lands as
 * KMP with its own file-store seam, this port can be satisfied by the KMP store
 * directly and [JvmOfflineSnapshotPort] can be deleted.
 */
interface OfflineSnapshotPort {
    /** The active snapshot's cache entry, or null when no snapshot is stored. */
    fun activeSnapshotEntry(feedId: FeedId): CacheEntry?
}

/**
 * Offline capability surface.
 *
 * Static network availability is derived from the local snapshot store; realtime
 * availability additionally requires a fresh cached snapshot. When realtime data
 * is not presentable the engine reports `realtimeUsable = false` so the UI can
 * hide live positions rather than draw stale ones as if they were moving.
 *
 * Loading the stored static feed itself ([loadStaticFeed], jvmMain) is a
 * JVM-only concern while `atlas-gtfs-static` is JVM-only: the parser needs
 * filesystem paths. This portable facade deliberately stops at the capability
 * report + live-vehicle gating so it compiles on wasmJs; hosts that need the
 * feed call the jvmMain loader.
 */
class OfflineRegistry(
    internal val snapshots: OfflineSnapshotPort? = null,
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
        now: Instant = Clock.System.now(),
    ): Capabilities {
        val staticEntry = snapshots?.activeSnapshotEntry(feedId)
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

    /** Realtime positions are only handed out while they are fresh. */
    fun liveVehicles(entry: CacheEntry?, vehicles: List<Vehicle>, now: Instant = Clock.System.now()): List<Vehicle> =
        if (staleness.isPresentableAsLive(entry, now)) vehicles else emptyList()
}
