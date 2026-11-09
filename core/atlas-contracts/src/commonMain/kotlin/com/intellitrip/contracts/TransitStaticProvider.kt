package com.intellitrip.contracts

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

/**
 * Provider-neutral static transit access.
 *
 * One-shot contract rule: every operation is a `suspend` function returning
 * `ProviderResult<T>`.
 */
interface TransitStaticProvider {
    suspend fun agencies(): ProviderResult<List<Agency>>
    suspend fun routes(agencyId: AgencyId? = null): ProviderResult<List<Route>>
    suspend fun stops(agencyId: AgencyId? = null): ProviderResult<List<Stop>>
    suspend fun trips(routeKey: RouteKey? = null): ProviderResult<List<Trip>>
    suspend fun stopTimes(tripKey: TripKey): ProviderResult<List<StopTime>>
    suspend fun calendar(): ProviderResult<List<Calendar>>
    suspend fun calendarDates(): ProviderResult<List<CalendarDate>>
    suspend fun shapes(routeKey: RouteKey? = null): ProviderResult<List<Shape>>
    suspend fun frequencies(): ProviderResult<List<Frequency>>
    suspend fun transfers(): ProviderResult<List<Transfer>>
    suspend fun feedMetadata(): ProviderResult<FeedMetadata>
}