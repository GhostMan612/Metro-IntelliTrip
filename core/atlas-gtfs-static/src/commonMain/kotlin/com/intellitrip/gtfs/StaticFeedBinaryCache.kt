package com.intellitrip.gtfs

import com.intellitrip.domain.Agency
import com.intellitrip.domain.AgencyId
import com.intellitrip.domain.Calendar
import com.intellitrip.domain.CalendarDate
import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import com.intellitrip.domain.Frequency
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.GtfsServiceTime
import com.intellitrip.domain.Route
import com.intellitrip.domain.RouteKey
import com.intellitrip.domain.ServiceExceptionType
import com.intellitrip.domain.ServiceKey
import com.intellitrip.domain.Shape
import com.intellitrip.domain.ShapeKey
import com.intellitrip.domain.Stop
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.Transfer
import com.intellitrip.domain.TransferType
import com.intellitrip.domain.Trip
import com.intellitrip.domain.TripKey
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * Binary cache for a parsed [StaticFeed] (stop_times excluded — those live in
 * the stop-time index). Cold start reads this in milliseconds instead of
 * re-parsing a 29 MB ZIP on a slow CPU.
 *
 * Serialized as bytes so it ports cleanly: the store keeps the file behind
 * [PlatformFileStore], and the wire format stays byte-identical to the old
 * platform data-stream writer.
 */
object StaticFeedBinaryCache {
    const val VERSION = 1
    const val CACHE_FILE = "static-feed.cache"

    fun write(feed: StaticFeed): ByteArray {
        val out = GrowableBytes()
        out.writeInt(VERSION)
        out.writeUtf8String(feed.feedId.value)
        writeMetadata(out, feed.metadata)
        out.writeInt(feed.agencies.size)
        feed.agencies.forEach { writeAgency(out, it) }
        out.writeInt(feed.routes.size)
        feed.routes.forEach { writeRoute(out, it) }
        out.writeInt(feed.stops.size)
        feed.stops.forEach { writeStop(out, it) }
        out.writeInt(feed.trips.size)
        feed.trips.forEach { writeTrip(out, it) }
        out.writeInt(feed.calendars.size)
        feed.calendars.forEach { writeCalendar(out, it) }
        out.writeInt(feed.calendarDates.size)
        feed.calendarDates.forEach { writeCalendarDate(out, it) }
        out.writeInt(feed.shapes.size)
        feed.shapes.forEach { writeShape(out, it) }
        out.writeInt(feed.frequencies.size)
        feed.frequencies.forEach { writeFrequency(out, it) }
        out.writeInt(feed.transfers.size)
        feed.transfers.forEach { writeTransfer(out, it) }
        return out.toByteArray()
    }

    fun read(bytes: ByteArray): StaticFeed? {
        return try {
            val input = ByteArrayCursor(bytes)
            val version = input.readInt()
            if (version != VERSION) throw IllegalArgumentException("cache version $version")
            val feedId = FeedId(input.readUtf8String())
            val metadata = readMetadata(input, feedId)
            val agencies = List(input.readInt()) { readAgency(input, feedId) }
            val routes = List(input.readInt()) { readRoute(input, feedId) }
            val stops = List(input.readInt()) { readStop(input, feedId) }
            val trips = List(input.readInt()) { readTrip(input, feedId) }
            val calendars = List(input.readInt()) { readCalendar(input, feedId) }
            val calendarDates = List(input.readInt()) { readCalendarDate(input, feedId) }
            val shapes = List(input.readInt()) { readShape(input, feedId) }
            val frequencies = List(input.readInt()) { readFrequency(input, feedId) }
            val transfers = List(input.readInt()) { readTransfer(input, feedId) }
            StaticFeed(
                feedId = feedId,
                metadata = metadata,
                agencies = agencies,
                routes = routes,
                stops = stops,
                trips = trips,
                stopTimes = emptyList(),
                calendars = calendars,
                calendarDates = calendarDates,
                shapes = shapes,
                frequencies = frequencies,
                transfers = transfers,
            )
        } catch (e: Exception) {
            println("StaticFeedBinaryCache: cache unreadable: ${e.message}")
            return null
        }
    }

    // ---- helpers ----
    private fun GrowableBytes.writeOptStr(s: String?) { writeBoolean(s != null); if (s != null) writeUtf8String(s) }
    private fun ByteArrayCursor.readOptStr(): String? = if (readBoolean()) readUtf8String() else null
    private fun GrowableBytes.writeOptInt(v: Int?) { writeBoolean(v != null); if (v != null) writeInt(v) }
    private fun ByteArrayCursor.readOptInt(): Int? = if (readBoolean()) readInt() else null
    private fun GrowableBytes.writeOptDate(d: LocalDate?) { writeBoolean(d != null); if (d != null) writeUtf8String(d.toString()) }
    private fun ByteArrayCursor.readOptDate(): LocalDate? = if (readBoolean()) LocalDate.parse(readUtf8String()) else null

    private fun writeMetadata(o: GrowableBytes, m: FeedMetadata) {
        o.writeOptStr(m.publisherName); o.writeOptStr(m.publisherUrl); o.writeOptStr(m.lang)
        o.writeOptStr(m.version); o.writeOptStr(m.hash); o.writeUtf8String(m.fetchedAt.toString())
        o.writeOptDate(m.validFrom); o.writeOptDate(m.validTo); o.writeOptStr(m.sourceUrl)
    }
    private fun readMetadata(i: ByteArrayCursor, feedId: FeedId): FeedMetadata = FeedMetadata(
        feedId = feedId,
        publisherName = i.readOptStr(), publisherUrl = i.readOptStr(), lang = i.readOptStr(),
        version = i.readOptStr(), hash = i.readOptStr(), fetchedAt = Instant.parse(i.readUtf8String()),
        validFrom = i.readOptDate(), validTo = i.readOptDate(), sourceUrl = i.readOptStr(),
    )

    private fun writeAgency(o: GrowableBytes, a: Agency) {
        o.writeUtf8String(a.id.value); o.writeUtf8String(a.name); o.writeOptStr(a.url)
        o.writeOptStr(a.timezone?.id); o.writeOptStr(a.lang); o.writeOptStr(a.phone)
        o.writeOptStr(a.fareUrl); o.writeOptStr(a.email)
    }
    private fun readAgency(i: ByteArrayCursor, feedId: FeedId): Agency = Agency(
        id = AgencyId(i.readUtf8String()), feedId = feedId, name = i.readUtf8String(), url = i.readOptStr(),
        timezone = i.readOptStr()?.let { runCatching { TimeZone.of(it) }.getOrNull() },
        lang = i.readOptStr(), phone = i.readOptStr(), fareUrl = i.readOptStr(), email = i.readOptStr(),
    )

    private fun writeRoute(o: GrowableBytes, r: Route) {
        o.writeUtf8String(r.key.routeId); o.writeOptStr(r.agencyId?.value)
        o.writeOptStr(r.shortName); o.writeOptStr(r.longName); o.writeOptStr(r.description)
        o.writeInt(r.routeType); o.writeOptStr(r.url); o.writeOptStr(r.color); o.writeOptStr(r.textColor)
        o.writeOptInt(r.sortOrder)
    }
    private fun readRoute(i: ByteArrayCursor, feedId: FeedId): Route = Route(
        key = RouteKey(feedId, i.readUtf8String()), agencyId = i.readOptStr()?.let { AgencyId(it) },
        shortName = i.readOptStr(), longName = i.readOptStr(), description = i.readOptStr(),
        routeType = i.readInt(), url = i.readOptStr(), color = i.readOptStr(), textColor = i.readOptStr(),
        sortOrder = i.readOptInt(),
    )

    private fun writeStop(o: GrowableBytes, s: Stop) {
        o.writeUtf8String(s.key.stopId); o.writeOptStr(s.code); o.writeUtf8String(s.name); o.writeOptStr(s.description)
        o.writeDouble(s.location.lat); o.writeDouble(s.location.lon); o.writeOptStr(s.url)
        o.writeOptInt(s.locationType); o.writeOptStr(s.parentStation?.stopId); o.writeOptStr(s.zoneId)
    }
    private fun readStop(i: ByteArrayCursor, feedId: FeedId): Stop = Stop(
        key = StopKey(feedId, i.readUtf8String()), code = i.readOptStr(), name = i.readUtf8String(), description = i.readOptStr(),
        location = GeoPoint(i.readDouble(), i.readDouble()), url = i.readOptStr(), locationType = i.readOptInt(),
        parentStation = i.readOptStr()?.let { StopKey(feedId, it) }, zoneId = i.readOptStr(),
    )

    private fun writeTrip(o: GrowableBytes, t: Trip) {
        o.writeUtf8String(t.key.tripId); o.writeUtf8String(t.routeKey.routeId); o.writeUtf8String(t.serviceKey.serviceId)
        o.writeOptStr(t.headsign); o.writeOptInt(t.directionId); o.writeOptStr(t.blockId)
        o.writeOptStr(t.shapeKey?.shapeId); o.writeOptInt(t.wheelchairAccessible)
    }
    private fun readTrip(i: ByteArrayCursor, feedId: FeedId): Trip = Trip(
        key = TripKey(feedId, i.readUtf8String()), routeKey = RouteKey(feedId, i.readUtf8String()),
        serviceKey = ServiceKey(feedId, i.readUtf8String()), headsign = i.readOptStr(), directionId = i.readOptInt(),
        blockId = i.readOptStr(), shapeKey = i.readOptStr()?.let { ShapeKey(feedId, it) },
        wheelchairAccessible = i.readOptInt(),
    )

    private fun writeCalendar(o: GrowableBytes, c: Calendar) {
        o.writeInt(c.daysOfWeek.size)
        c.daysOfWeek.forEach { o.writeUtf8String(it.name) }
        o.writeUtf8String(c.serviceKey.serviceId)
        o.writeUtf8String(c.startDate.toString()); o.writeUtf8String(c.endDate.toString())
    }
    private fun readCalendar(i: ByteArrayCursor, feedId: FeedId): Calendar {
        val n = i.readInt()
        val days = (0 until n).map { DayOfWeek.valueOf(i.readUtf8String()) }.toSet()
        return Calendar(
            serviceKey = ServiceKey(feedId, i.readUtf8String()), daysOfWeek = days,
            startDate = LocalDate.parse(i.readUtf8String()), endDate = LocalDate.parse(i.readUtf8String()),
        )
    }

    private fun writeCalendarDate(o: GrowableBytes, c: CalendarDate) {
        o.writeUtf8String(c.serviceKey.serviceId); o.writeUtf8String(c.date.toString()); o.writeUtf8String(c.exceptionType.name)
    }
    private fun readCalendarDate(i: ByteArrayCursor, feedId: FeedId): CalendarDate = CalendarDate(
        serviceKey = ServiceKey(feedId, i.readUtf8String()), date = LocalDate.parse(i.readUtf8String()),
        exceptionType = ServiceExceptionType.valueOf(i.readUtf8String()),
    )

    private fun writeShape(o: GrowableBytes, s: Shape) {
        o.writeInt(s.points.size)
        s.points.forEach { o.writeDouble(it.lat); o.writeDouble(it.lon) }
        o.writeUtf8String(s.key.shapeId)
    }
    private fun readShape(i: ByteArrayCursor, feedId: FeedId): Shape {
        val n = i.readInt()
        val pts = List(n) { GeoPoint(i.readDouble(), i.readDouble()) }
        return Shape(key = ShapeKey(feedId, i.readUtf8String()), points = pts)
    }

    private fun writeFrequency(o: GrowableBytes, f: Frequency) {
        o.writeUtf8String(f.tripKey.tripId); o.writeInt(f.startTime.secondsSinceServiceDayStart)
        o.writeInt(f.endTime.secondsSinceServiceDayStart); o.writeInt(f.headwaySeconds); o.writeOptInt(f.exactTimes)
    }
    private fun readFrequency(i: ByteArrayCursor, feedId: FeedId): Frequency = Frequency(
        tripKey = TripKey(feedId, i.readUtf8String()), startTime = GtfsServiceTime.ofSeconds(i.readInt()),
        endTime = GtfsServiceTime.ofSeconds(i.readInt()), headwaySeconds = i.readInt(), exactTimes = i.readOptInt(),
    )

    private fun writeTransfer(o: GrowableBytes, t: Transfer) {
        o.writeUtf8String(t.fromStopKey.stopId); o.writeUtf8String(t.toStopKey.stopId); o.writeUtf8String(t.type.name); o.writeOptInt(t.minTransferSeconds)
    }
    private fun readTransfer(i: ByteArrayCursor, feedId: FeedId): Transfer = Transfer(
        fromStopKey = StopKey(feedId, i.readUtf8String()), toStopKey = StopKey(feedId, i.readUtf8String()),
        type = TransferType.valueOf(i.readUtf8String()), minTransferSeconds = i.readOptInt(),
    )
}
