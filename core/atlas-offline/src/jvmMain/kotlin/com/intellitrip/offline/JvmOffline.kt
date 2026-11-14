package com.intellitrip.offline

import com.intellitrip.domain.FeedId
import com.intellitrip.gtfs.GtfsArchive
import com.intellitrip.gtfs.GtfsStaticParser
import com.intellitrip.gtfs.StaticFeed
import com.intellitrip.gtfs.StaticFeedSnapshotStore

/**
 * Maps the [StaticFeedSnapshotStore] onto the portable [OfflineSnapshotPort].
 *
 * This adapter lives in jvmMain because [loadStaticFeed] needs filesystem
 * paths + the gtfs-static payload bytes (both available on JVM next to the
 * store). The registry's capabilities/live-vehicles logic is portable and
 * stays in commonMain.
 */
class JvmOfflineSnapshotPort(
    val store: StaticFeedSnapshotStore,
) : OfflineSnapshotPort {

    override fun activeSnapshotEntry(feedId: FeedId): CacheEntry? =
        store.activeSnapshot(feedId)?.metadata?.let { metadata ->
            CacheEntry(
                feedId = feedId,
                fetchedAt = metadata.fetchedAt,
                validityStart = metadata.validFrom,
                validityEnd = metadata.validTo,
                version = metadata.version,
                hash = metadata.hash,
            )
        }
}

/**
 * Loads and parses the stored static snapshot; null when nothing is stored.
 *
 * Stop times are deliberately discarded here: they are already on disk in the
 * snapshot's stop-time index and are read back on demand by the planner. Letting
 * the parser collect them would rebuild the whole table in memory on every cold
 * start, which is what the on-disk index exists to avoid.
 *
 * Passing [includeShapes] = false skips deserializing `shapes.txt` geometries
 * when using the v2 binary cache (and automatically upgrades a v1 binary cache
 * to v2), cutting cold-start time and heap usage for routing-only hosts.
 *
 * JVM-only while `atlas-gtfs-static` is JVM-only (the parser needs filesystem
 * paths): this is why it is an extension on the portable [OfflineRegistry]
 * rather than a member of it, so commonMain compiles on wasmJs. Hosts in other
 * packages need `import com.intellitrip.offline.loadStaticFeed`.
 */
@JvmOverloads
fun OfflineRegistry.loadStaticFeed(feedId: FeedId, includeShapes: Boolean = true): StaticFeed? {
    val port = snapshots as? JvmOfflineSnapshotPort ?: return null
    val store = port.store
    val snapshot = store.activeSnapshot(feedId) ?: return null
    // Fast path: binary cache (no CSV parse). Validated against snapshot hash.
    store.readCache(feedId)?.let { cacheBytes ->
        val cacheVersion = com.intellitrip.gtfs.StaticFeedBinaryCache.versionOf(cacheBytes)
        val upgradeV1ToV2 = cacheVersion == com.intellitrip.gtfs.StaticFeedBinaryCache.LEGACY_VERSION_1 &&
            !includeShapes
        com.intellitrip.gtfs.StaticFeedBinaryCache.read(
            cacheBytes,
            includeShapes = includeShapes || upgradeV1ToV2,
        )?.let { cached ->
            if (cached.metadata.hash == snapshot.metadata.hash) {
                if (upgradeV1ToV2) {
                    runCatching {
                        store.writeCache(feedId, cached, expectedSnapshotDir = snapshot.snapshotDir)
                    }
                }
                return if (includeShapes) cached else cached.copy(shapes = emptyList())
            }
        }
    }
    if (store.payloadPath(feedId) != null) {
        val parsed = runCatching {
            GtfsStaticParser.parseFromZip(
                feedId,
                store.readPayload(feedId),
                snapshot.metadata,
                com.intellitrip.gtfs.StopTimeSink { },
            )
        }.getOrNull() ?: return null
        runCatching { store.writeCache(feedId, parsed, expectedSnapshotDir = snapshot.snapshotDir) }
        return if (includeShapes) parsed else parsed.copy(shapes = emptyList())
    }
    val payload = runCatching { store.readPayload(feedId) }.getOrNull() ?: return null
    val files = runCatching { GtfsArchive.read(payload).toMutableMap() }.getOrNull() ?: return null
    val parsed = runCatching {
        GtfsStaticParser.parse(
            feedId,
            files,
            snapshot.metadata,
            com.intellitrip.gtfs.StopTimeSink { },
        )
    }.getOrNull() ?: return null
    return if (includeShapes) parsed else parsed.copy(shapes = emptyList())
}
