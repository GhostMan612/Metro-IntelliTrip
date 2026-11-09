package com.intellitrip

import android.content.Context
import com.intellitrip.engine.AtlasEngine
import com.intellitrip.engine.AtlasProviders
import com.intellitrip.contracts.LocationProvider
import com.intellitrip.domain.FeedId
import com.intellitrip.location.AndroidLocationProvider
import com.intellitrip.gtfs.GtfsFeedAcquirer
import com.intellitrip.gtfs.StaticFeedRefresher
import com.intellitrip.gtfs.StaticFeedSnapshotStore
import com.intellitrip.gtfs.StaticFeed
import com.intellitrip.gtfsrt.PollingGtfsRealtimeClient
import com.intellitrip.gtfsrt.UrlGtfsRealtimeFetcher
import com.intellitrip.map.RenderUpdateThrottle
import com.intellitrip.map.TransitRenderPipeline
import com.intellitrip.offline.OfflineRegistry
import com.intellitrip.routing.StaticNetworkPlanner
import com.intellitrip.routing.RoutingNetwork
import com.intellitrip.scope.ScopeEngine
import com.intellitrip.weather.NwsWeatherAlertProvider
import com.intellitrip.weather.RainViewerRadarProvider
import com.intellitrip.weather.WeatherHttpClient
import com.intellitrip.weather.WeatherRequestContext
import kotlinx.coroutines.runBlocking

const val FEED_ID = "metro-transit-regional"
const val DEFAULT_LAT = 44.9765
const val DEFAULT_LON = -93.2650

// Downtown St Paul. Deliberately a real transit destination: the planner pair must
// have scheduled service between them, otherwise the app opens on "0 journeys" and
// looks broken. Verified against the Metro Transit feed.
const val DEFAULT_DEST_LAT = 44.9580
const val DEFAULT_DEST_LON = -93.1560
const val USER_AGENT = "IntelliTrip/0.1 (https://github.com/GhostMan612/Metro-IntelliTrip)"

const val STATIC_GTFS_URL = "https://svc.metrotransit.org/mtgtfs/gtfs.zip"

val REALTIME_URLS = PollingGtfsRealtimeClient.RealtimeFeedUrls(
    vehiclePositions = "https://svc.metrotransit.org/mtgtfs/vehiclepositions.pb",
    tripUpdates = "https://svc.metrotransit.org/mtgtfs/tripupdates.pb",
    alerts = "https://svc.metrotransit.org/mtgtfs/alerts.pb",
)

/**
 * Composition root for the Android app.
 *
 * Every provider is constructed here and injected into the engine, so the app
 * adds no domain logic of its own and could swap any adapter in one place.
 */
class AtlasHost(val context: Context) {

    val feedId: FeedId = FeedId(FEED_ID)

    /** Platform GPS. A contract, so the engine never sees an Android type. */
    /**
     * Platform GPS. A contract, so the engine never sees an Android type.
     *
     * Last-known fixes are not pre-seeded on purpose: the platform happily returns
     * one from days ago when GPS has never warmed up indoors, and presenting that as
     * the user's position would be worse than showing nothing. Callers get a fix only
     * once something live arrives.
     */
    val location: LocationProvider = AndroidLocationProvider(context)

    val snapshotStore = StaticFeedSnapshotStore(context.filesDir.toPath())

    /**
     * Private on-device storage for rider-owned data.
     *
     * Distinct from the GTFS snapshot root so a feed refresh, which prunes superseded
     * snapshots, can never delete something the rider saved.
     */
    fun filesRoot(): java.nio.file.Path = context.filesDir.toPath().resolve("rider")

    /**
     * The live renderer, once one exists.
     *
     * Null before the map finishes constructing. Hit testing needs the real renderer
     * because it queries rendered features; routing the tap through the host rather
     * than the composable keeps the MapCanvas from having to publish the renderer as
     * mutable state that can be observed mid-frame.
     */
    @Volatile
    var rendererRef: (() -> com.intellitrip.map.android.MapLibreMapRenderer?)? = null
        private set

    /** Registers the renderer once created and returns it to later callers. */
    fun attachRenderer(renderer: com.intellitrip.map.android.MapLibreMapRenderer?) {
        rendererRef = { renderer }
    }

    fun currentRenderer(): com.intellitrip.map.android.MapLibreMapRenderer? = rendererRef?.invoke()

    /**
     * Seeds the snapshot store from the bundled debug GTFS (assets/offline/debug-gtfs.zip)
     * when no snapshot exists. Lets the BLU verify V1 flows instantly without a
     * 4-minute full-feed parse. Debug builds only intent: production should
     * download the full bundle. Returns true if seeded.
     */
    fun seedDebugFeedIfNeeded(): Boolean {
        if (snapshotStore.activeSnapshot(feedId) != null) return false
        val payload = runCatching {
            context.assets.open("offline/debug-gtfs.zip").use { it.readBytes() }
        }.getOrNull() ?: return false
        val transport = com.intellitrip.gtfs.GtfsFeedAcquirer.HttpTransport {
            com.intellitrip.gtfs.GtfsFeedAcquirer.HttpResponse(200, payload, mapOf("ETag" to "debug"))
        }
        val refresher = com.intellitrip.gtfs.StaticFeedRefresher(
            feedId = feedId,
            sourceUrl = "asset://offline/debug-gtfs.zip",
            acquirer = com.intellitrip.gtfs.GtfsFeedAcquirer(transport = transport),
            store = snapshotStore,
        )
        return runBlocking(kotlinx.coroutines.Dispatchers.IO) { refresher.refresh() } is com.intellitrip.gtfs.RefreshOutcome.Activated
    }

    val offlineRegistry = OfflineRegistry(snapshotStore)
    val basemapStore = com.intellitrip.offline.OfflineBasemapStore(
        context.filesDir.toPath().resolve("basemaps"),
    )
    val scopeEngine = ScopeEngine()
    val pipeline = TransitRenderPipeline()
    val throttle = RenderUpdateThrottle()

    private val http = WeatherHttpClient.urlConnection()

    val staticRefresher = StaticFeedRefresher(
        feedId = feedId,
        sourceUrl = STATIC_GTFS_URL,
        acquirer = GtfsFeedAcquirer(userAgent = USER_AGENT),
        store = snapshotStore,
    )

    val realtime = PollingGtfsRealtimeClient(
        feedId = feedId,
        urls = REALTIME_URLS,
        fetcher = UrlGtfsRealtimeFetcher(userAgent = USER_AGENT),
    )

    val radar = RainViewerRadarProvider(http = http, context = WeatherRequestContext("rainviewer", USER_AGENT))

    val weatherAlerts = NwsWeatherAlertProvider(
        http = http,
        context = WeatherRequestContext("nws", USER_AGENT),
    )

    /** Routing is built from the stored static network so it also works offline. */
    fun routingPlanner(network: RoutingNetwork?): StaticNetworkPlanner? =
        network?.let { StaticNetworkPlanner(it) }

    /**
     * Builds a routing network from a feed, reading stop times from the
     * disk-backed index when one is available so memory stays bounded.
     */
    fun routingNetwork(feed: StaticFeed): RoutingNetwork {
        val reader = snapshotStore.stopTimeReader(feedId)
        val source = reader?.let { index ->
            object : com.intellitrip.routing.StopTimeSource {
                override fun stopTimesFor(trip: com.intellitrip.domain.TripKey) =
                    index.stopTimesFor(trip) { stopId -> com.intellitrip.domain.StopKey(feedId, stopId) }

                override fun tripsServing(stop: com.intellitrip.domain.StopKey) = index.tripsServing(stop)
            }
        }
        return RoutingNetwork(
            feedId = feed.feedId,
            metadata = feed.metadata,
            routes = feed.routes,
            stops = feed.stops,
            trips = feed.trips,
            stopTimes = feed.stopTimes,
            stopTimeSource = source,
            transfers = feed.transfers,
            calendars = feed.calendars,
            calendarDates = feed.calendarDates,
            agencies = feed.agencies,
            frequencies = feed.frequencies,
        )
    }

    fun engine(providers: AtlasProviders = AtlasProviders()): AtlasEngine = AtlasEngine(
        feedId = feedId,
        providers = providers,
        offlineAvailable = true,
        scopeEngine = scopeEngine,
        renderPipeline = pipeline,
    )

    /** Engine whose routing provider is the local static planner over [network]. */
    fun engineForRouting(network: RoutingNetwork): AtlasEngine =
        engine(AtlasProviders(routing = StaticNetworkPlanner(network)))
}