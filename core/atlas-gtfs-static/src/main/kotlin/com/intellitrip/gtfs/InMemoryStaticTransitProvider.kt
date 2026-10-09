package com.intellitrip.gtfs

import com.intellitrip.contracts.FreshnessMetadata
import com.intellitrip.contracts.ProviderResult
import com.intellitrip.contracts.TransitStaticProvider
import com.intellitrip.domain.Agency
import com.intellitrip.domain.AgencyId
import com.intellitrip.domain.Calendar
import com.intellitrip.domain.CalendarDate
import com.intellitrip.domain.FeedMetadata
import com.intellitrip.domain.Frequency
import com.intellitrip.domain.Route
import com.intellitrip.domain.RouteKey
import com.intellitrip.domain.Shape
import com.intellitrip.domain.Stop
import com.intellitrip.domain.StopTime
import com.intellitrip.domain.Transfer
import com.intellitrip.domain.Trip
import com.intellitrip.domain.TripKey
import java.time.Duration

/** Read-only [TransitStaticProvider] backed by an in-memory [StaticFeed]. */
class InMemoryStaticTransitProvider(
    private val feed: StaticFeed,
    private val providerId: String = "gtfs-static",
) : TransitStaticProvider {

    private fun freshness(): FreshnessMetadata = FreshnessMetadata(
        sourceTimestamp = feed.metadata.fetchedAt,
        fetchedAt = feed.metadata.fetchedAt,
        age = Duration.ZERO,
        providerId = providerId,
    )

    override suspend fun agencies(): ProviderResult<List<Agency>> =
        ProviderResult.Success(feed.agencies, freshness())

    override suspend fun routes(agencyId: AgencyId?): ProviderResult<List<Route>> =
        ProviderResult.Success(feed.routesForAgency(agencyId), freshness())

    override suspend fun stops(agencyId: AgencyId?): ProviderResult<List<Stop>> =
        ProviderResult.Success(feed.stopsForAgency(agencyId), freshness())

    override suspend fun trips(routeKey: RouteKey?): ProviderResult<List<Trip>> =
        ProviderResult.Success(feed.tripsForRoute(routeKey), freshness())

    override suspend fun stopTimes(tripKey: TripKey): ProviderResult<List<StopTime>> =
        ProviderResult.Success(feed.stopTimesByTrip[tripKey].orEmpty(), freshness())

    override suspend fun calendar(): ProviderResult<List<Calendar>> =
        ProviderResult.Success(feed.calendars, freshness())

    override suspend fun calendarDates(): ProviderResult<List<CalendarDate>> =
        ProviderResult.Success(feed.calendarDates, freshness())

    override suspend fun shapes(routeKey: RouteKey?): ProviderResult<List<Shape>> {
        val routeTrips = feed.tripsForRoute(routeKey)
        val shapeKeys = routeTrips.mapNotNull { it.shapeKey }.toSet()
        return ProviderResult.Success(feed.shapes.filter { it.key in shapeKeys }, freshness())
    }

    override suspend fun frequencies(): ProviderResult<List<Frequency>> =
        ProviderResult.Success(feed.frequencies, freshness())

    override suspend fun transfers(): ProviderResult<List<Transfer>> =
        ProviderResult.Success(feed.transfers, freshness())

    override suspend fun feedMetadata(): ProviderResult<FeedMetadata> =
        ProviderResult.Success(feed.metadata, freshness())
}