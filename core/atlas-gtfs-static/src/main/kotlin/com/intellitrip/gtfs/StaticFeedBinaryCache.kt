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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * Binary cache for a parsed [StaticFeed] (stop_times excluded — those live in
 * the on-disk index). Cold start reads this in milliseconds instead of
 * re-parsing a 29 MB ZIP on a slow CPU.
 */
object StaticFeedBinaryCache {
    const val VERSION = 1
    const val CACHE_FILE = "static-feed.cache"

    fun write(feed: StaticFeed, path: Path) {
        Files.createDirectories(path.parent)
        Files.newOutputStream(path).use { raw ->
            DataOutputStream(BufferedOutputStream(raw, 16384)).use { out ->
                out.writeInt(VERSION)
                out.writeUTF(feed.feedId.value)
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
            }
        }
    }

    fun read(path: Path): StaticFeed? {
        if (!Files.exists(path)) return null
        return try {
            Files.newInputStream(path).use { raw ->
                DataInputStream(BufferedInputStream(raw, 16384)).use { input ->
                    val version = input.readInt()
                    if (version != VERSION) throw java.io.IOException("cache version $version")
                    val feedId = FeedId(input.readUTF())
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
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // ---- helpers ----
    private fun DataOutputStream.writeOptStr(s: String?) { writeBoolean(s != null); if (s != null) writeUTF(s) }
    private fun DataInputStream.readOptStr(): String? = if (readBoolean()) readUTF() else null
    private fun DataOutputStream.writeOptInt(v: Int?) { writeBoolean(v != null); if (v != null) writeInt(v) }
    private fun DataInputStream.readOptInt(): Int? = if (readBoolean()) readInt() else null
    private fun DataOutputStream.writeOptDouble(v: Double?) { writeBoolean(v != null); if (v != null) writeDouble(v) }
    private fun DataInputStream.readOptDouble(): Double? = if (readBoolean()) readDouble() else null
    private fun DataOutputStream.writeOptDate(d: LocalDate?) { writeBoolean(d != null); if (d != null) writeUTF(d.toString()) }
    private fun DataInputStream.readOptDate(): LocalDate? = if (readBoolean()) LocalDate.parse(readUTF()) else null

    private fun writeMetadata(o: DataOutputStream, m: FeedMetadata) {
        o.writeOptStr(m.publisherName); o.writeOptStr(m.publisherUrl); o.writeOptStr(m.lang)
        o.writeOptStr(m.version); o.writeOptStr(m.hash); o.writeUTF(m.fetchedAt.toString())
        o.writeOptDate(m.validFrom); o.writeOptDate(m.validTo); o.writeOptStr(m.sourceUrl)
    }
    private fun readMetadata(i: DataInputStream, feedId: FeedId): FeedMetadata = FeedMetadata(
        feedId = feedId,
        publisherName = i.readOptStr(), publisherUrl = i.readOptStr(), lang = i.readOptStr(),
        version = i.readOptStr(), hash = i.readOptStr(), fetchedAt = Instant.parse(i.readUTF()),
        validFrom = i.readOptDate(), validTo = i.readOptDate(), sourceUrl = i.readOptStr(),
    )

    private fun writeAgency(o: DataOutputStream, a: Agency) {
        o.writeUTF(a.id.value); o.writeUTF(a.name); o.writeOptStr(a.url)
        o.writeOptStr(a.timezone?.id); o.writeOptStr(a.lang); o.writeOptStr(a.phone)
        o.writeOptStr(a.fareUrl); o.writeOptStr(a.email)
    }
    private fun readAgency(i: DataInputStream, feedId: FeedId): Agency = Agency(
        id = AgencyId(i.readUTF()), feedId = feedId, name = i.readUTF(), url = i.readOptStr(),
        timezone = i.readOptStr()?.let { runCatching { TimeZone.of(it) }.getOrNull() },
        lang = i.readOptStr(), phone = i.readOptStr(), fareUrl = i.readOptStr(), email = i.readOptStr(),
    )

    private fun writeRoute(o: DataOutputStream, r: Route) {
        o.writeUTF(r.key.routeId); o.writeOptStr(r.agencyId?.value)
        o.writeOptStr(r.shortName); o.writeOptStr(r.longName); o.writeOptStr(r.description)
        o.writeInt(r.routeType); o.writeOptStr(r.url); o.writeOptStr(r.color); o.writeOptStr(r.textColor)
        o.writeOptInt(r.sortOrder)
    }
    private fun readRoute(i: DataInputStream, feedId: FeedId): Route = Route(
        key = RouteKey(feedId, i.readUTF()), agencyId = i.readOptStr()?.let { AgencyId(it) },
        shortName = i.readOptStr(), longName = i.readOptStr(), description = i.readOptStr(),
        routeType = i.readInt(), url = i.readOptStr(), color = i.readOptStr(), textColor = i.readOptStr(),
        sortOrder = i.readOptInt(),
    )

    private fun writeStop(o: DataOutputStream, s: Stop) {
        o.writeUTF(s.key.stopId); o.writeOptStr(s.code); o.writeUTF(s.name); o.writeOptStr(s.description)
        o.writeDouble(s.location.lat); o.writeDouble(s.location.lon); o.writeOptStr(s.url)
        o.writeOptInt(s.locationType); o.writeOptStr(s.parentStation?.stopId); o.writeOptStr(s.zoneId)
    }
    private fun readStop(i: DataInputStream, feedId: FeedId): Stop = Stop(
        key = StopKey(feedId, i.readUTF()), code = i.readOptStr(), name = i.readUTF(), description = i.readOptStr(),
        location = GeoPoint(i.readDouble(), i.readDouble()), url = i.readOptStr(), locationType = i.readOptInt(),
        parentStation = i.readOptStr()?.let { StopKey(feedId, it) }, zoneId = i.readOptStr(),
    )

    private fun writeTrip(o: DataOutputStream, t: Trip) {
        o.writeUTF(t.key.tripId); o.writeUTF(t.routeKey.routeId); o.writeUTF(t.serviceKey.serviceId)
        o.writeOptStr(t.headsign); o.writeOptInt(t.directionId); o.writeOptStr(t.blockId)
        o.writeOptStr(t.shapeKey?.shapeId); o.writeOptInt(t.wheelchairAccessible)
    }
    private fun readTrip(i: DataInputStream, feedId: FeedId): Trip = Trip(
        key = TripKey(feedId, i.readUTF()), routeKey = RouteKey(feedId, i.readUTF()),
        serviceKey = ServiceKey(feedId, i.readUTF()), headsign = i.readOptStr(), directionId = i.readOptInt(),
        blockId = i.readOptStr(), shapeKey = i.readOptStr()?.let { ShapeKey(feedId, it) },
        wheelchairAccessible = i.readOptInt(),
    )

    private fun writeCalendar(o: DataOutputStream, c: Calendar) {
        o.writeInt(c.daysOfWeek.size)
        c.daysOfWeek.forEach { o.writeUTF(it.name) }
        o.writeUTF(c.serviceKey.serviceId)
        o.writeUTF(c.startDate.toString()); o.writeUTF(c.endDate.toString())
    }
    private fun readCalendar(i: DataInputStream, feedId: FeedId): Calendar {
        val n = i.readInt()
        val days = (0 until n).map { DayOfWeek.valueOf(i.readUTF()) }.toSet()
        return Calendar(
            serviceKey = ServiceKey(feedId, i.readUTF()), daysOfWeek = days,
            startDate = LocalDate.parse(i.readUTF()), endDate = LocalDate.parse(i.readUTF()),
        )
    }

    private fun writeCalendarDate(o: DataOutputStream, c: CalendarDate) {
        o.writeUTF(c.serviceKey.serviceId); o.writeUTF(c.date.toString()); o.writeUTF(c.exceptionType.name)
    }
    private fun readCalendarDate(i: DataInputStream, feedId: FeedId): CalendarDate = CalendarDate(
        serviceKey = ServiceKey(feedId, i.readUTF()), date = LocalDate.parse(i.readUTF()),
        exceptionType = ServiceExceptionType.valueOf(i.readUTF()),
    )

    private fun writeShape(o: DataOutputStream, s: Shape) {
        o.writeInt(s.points.size)
        s.points.forEach { o.writeDouble(it.lat); o.writeDouble(it.lon) }
        o.writeUTF(s.key.shapeId)
    }
    private fun readShape(i: DataInputStream, feedId: FeedId): Shape {
        val n = i.readInt()
        val pts = List(n) { GeoPoint(i.readDouble(), i.readDouble()) }
        return Shape(key = ShapeKey(feedId, i.readUTF()), points = pts)
    }

    private fun writeFrequency(o: DataOutputStream, f: Frequency) {
        o.writeUTF(f.tripKey.tripId); o.writeInt(f.startTime.secondsSinceServiceDayStart)
        o.writeInt(f.endTime.secondsSinceServiceDayStart); o.writeInt(f.headwaySeconds); o.writeOptInt(f.exactTimes)
    }
    private fun readFrequency(i: DataInputStream, feedId: FeedId): Frequency = Frequency(
        tripKey = TripKey(feedId, i.readUTF()), startTime = GtfsServiceTime.ofSeconds(i.readInt()),
        endTime = GtfsServiceTime.ofSeconds(i.readInt()), headwaySeconds = i.readInt(), exactTimes = i.readOptInt(),
    )

    private fun writeTransfer(o: DataOutputStream, t: Transfer) {
        o.writeUTF(t.fromStopKey.stopId); o.writeUTF(t.toStopKey.stopId); o.writeUTF(t.type.name); o.writeOptInt(t.minTransferSeconds)
    }
    private fun readTransfer(i: DataInputStream, feedId: FeedId): Transfer = Transfer(
        fromStopKey = StopKey(feedId, i.readUTF()), toStopKey = StopKey(feedId, i.readUTF()),
        type = TransferType.valueOf(i.readUTF()), minTransferSeconds = i.readOptInt(),
    )
}
