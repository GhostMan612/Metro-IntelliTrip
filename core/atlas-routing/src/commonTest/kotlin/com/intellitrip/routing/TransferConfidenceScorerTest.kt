package com.intellitrip.routing

import com.intellitrip.domain.JourneyOption
import com.intellitrip.domain.Leg
import com.intellitrip.domain.RealtimeStatus
import com.intellitrip.domain.ScheduleRelationship
import com.intellitrip.domain.Transfer
import com.intellitrip.domain.TransferConfidence
import com.intellitrip.domain.TransferConnection
import com.intellitrip.domain.TransferType
import com.intellitrip.domain.TripUpdate
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TransferConfidenceScorerTest {

    private val scorer = TransferConfidenceScorer()
    private val now = Instant.parse("2026-10-05T13:00:00Z")

    private fun connection(
        buffer: Duration,
        predicted: Duration? = null,
        departingStatus: RealtimeStatus = RealtimeStatus.ON_TIME,
    ): TransferConnection {
        val arriving = transitLeg("T1", Depart = 0)
        val departing = transitLeg("T2", Depart = buffer.inWholeSeconds, status = departingStatus)
        return TransferConnection(
            arrivingLeg = arriving,
            departingLeg = departing,
            transferStop = RoutingFixtures.stops[1].key,
            scheduledBuffer = buffer,
            predictedBuffer = predicted,
            walkingDuration = Duration.ZERO,
            confidence = TransferConfidence.SCHEDULED_UNKNOWN,
            rationale = null,
        )
    }

    private fun transitLeg(
        tripId: String,
        Depart: Long,
        status: RealtimeStatus = RealtimeStatus.ON_TIME,
    ): Leg.Transit {
        val departure = LocalDateTime(RoutingFixtures.serviceDate.year, RoutingFixtures.serviceDate.monthNumber, RoutingFixtures.serviceDate.dayOfMonth, 8, 0).toInstant(RoutingFixtures.zone) + Depart.seconds
        return Leg.Transit(
            agencyId = RoutingFixtures.agency.id,
            routeKey = RoutingFixtures.routeA.key,
            tripKey = RoutingFixtures.tripA1.key.copy(),
            serviceDate = RoutingFixtures.serviceDate,
            fromStopKey = RoutingFixtures.stops[1].key,
            toStopKey = RoutingFixtures.stops[2].key,
            departure = departure,
            arrival = departure + 300.seconds,
            vehicleKey = null,
            realtimeStatus = status,
            delaySeconds = 0,
            scheduledDeparture = departure,
            scheduledArrival = departure + 300.seconds,
            duration = 300.seconds,
        ).let { leg -> leg.copy(tripKey = com.intellitrip.domain.TripKey(leg.tripKey.feedId, tripId)) }
    }

    private fun update(tripId: String, timestamp: Instant = now): TripUpdate = TripUpdate(
        tripKey = com.intellitrip.domain.TripKey(RoutingFixtures.feedId, tripId),
        routeKey = RoutingFixtures.routeA.key,
        vehicleKey = null,
        directionId = 0,
        startDate = null,
        startTime = null,
        timestamp = timestamp,
        scheduleRelationship = ScheduleRelationship.SCHEDULED,
        stopTimeUpdates = emptyList(),
    )

    @Test
    fun withoutRealtimeInformationConfidenceIsUnknown() {
        val scored = scorer.score(
            TransferConfidenceScorer.Inputs(connection(6.minutes), now = now)
        )
        assertEquals(TransferConfidence.SCHEDULED_UNKNOWN, scored.confidence)
        assertNotNull(scored.rationale)
    }

    @Test
    fun staleRealtimeInformationDoesNotClaimCertainty() {
        val scored = scorer.score(
            TransferConfidenceScorer.Inputs(
                connection = connection(6.minutes),
                arrivingUpdate = update("T1", now - 3_600.seconds),
                departingUpdate = update("T2", now - 3_600.seconds),
                now = now,
            )
        )
        assertEquals(TransferConfidence.SCHEDULED_UNKNOWN, scored.confidence)
        assertTrue(scored.rationale!!.contains("stale"))
    }

    @Test
    fun generousBufferIsHighConfidence() {
        val scored = scorer.score(
            TransferConfidenceScorer.Inputs(
                connection = connection(8.minutes),
                arrivingUpdate = update("T1"),
                departingUpdate = update("T2"),
                now = now,
            )
        )
        assertEquals(TransferConfidence.HIGH, scored.confidence)
    }

    @Test
    fun tightBufferIsMediumConfidence() {
        val scored = scorer.score(
            TransferConfidenceScorer.Inputs(
                connection = connection(3.minutes),
                arrivingUpdate = update("T1"),
                departingUpdate = update("T2"),
                now = now,
            )
        )
        assertEquals(TransferConfidence.MEDIUM, scored.confidence)
    }

    @Test
    fun bufferBelowMinimumTransferTimeIsLowConfidence() {
        val scored = scorer.score(
            TransferConfidenceScorer.Inputs(
                connection = connection(3.minutes),
                arrivingUpdate = update("T1"),
                departingUpdate = update("T2"),
                transfer = Transfer(
                    fromStopKey = RoutingFixtures.stops[1].key,
                    toStopKey = RoutingFixtures.stops[1].key,
                    type = TransferType.TIMED,
                    minTransferSeconds = 300,
                ),
                now = now,
            )
        )
        assertEquals(TransferConfidence.LOW, scored.confidence)
        assertTrue(scored.rationale!!.contains("minimum transfer time"))
    }

    @Test
    fun negativePredictedBufferIsLowConfidence() {
        val scored = scorer.score(
            TransferConfidenceScorer.Inputs(
                connection = connection(
                    buffer = 6.minutes,
                    predicted = (-30).seconds,
                ),
                arrivingUpdate = update("T1"),
                departingUpdate = update("T2"),
                now = now,
            )
        )
        assertEquals(TransferConfidence.LOW, scored.confidence)
    }

    @Test
    fun canceledDepartureIsLowConfidence() {
        val scored = scorer.score(
            TransferConfidenceScorer.Inputs(
                connection = connection(6.minutes, departingStatus = RealtimeStatus.CANCELED),
                arrivingUpdate = update("T1"),
                departingUpdate = update("T2"),
                now = now,
            )
        )
        assertEquals(TransferConfidence.LOW, scored.confidence)
        assertTrue(scored.rationale!!.contains("canceled"))
    }

    @Test
    fun everyOutcomeCarriesRationale() {
        val results = listOf(
            TransferConfidenceScorer.Inputs(connection(6.minutes)),
            TransferConfidenceScorer.Inputs(
                connection = connection(8.minutes),
                arrivingUpdate = update("T1"),
                departingUpdate = update("T2"),
            ),
        ).map { scorer.score(it) }
        assertTrue(results.all { !it.rationale.isNullOrBlank() })
    }
}

class TransferIntelligenceServiceTest {

    private val service = TransferIntelligenceService()
    private val now = Instant.parse("2026-10-05T13:00:00Z")

    private fun journeyWithConnection(): JourneyOption {
        val planner = StaticNetworkPlanner(RoutingFixtures.network, RoutingFixtures.zone)
        val journeys = kotlinx.coroutines.runBlocking {
            planner.plan(RoutingFixtures.request())
        }
        val journey = (journeys as com.intellitrip.contracts.ProviderResult.Success).data
            .first { it.transfers.isNotEmpty() }
        return journey
    }

    @Test
    fun scoresConnectionsAndReportsWorstConfidence() {
        val journey = journeyWithConnection()
        val report = service.evaluate(journey, emptyList(), emptyList(), now)
        assertNotNull(report.worstConfidence)
        assertEquals(journey.transfers.size, report.journey.transfers.size)
        // with no realtime data the service must not overstate confidence
        assertEquals(TransferConfidence.SCHEDULED_UNKNOWN, report.worstConfidence)
    }

    @Test
    fun journeyItselfCarriesNoConfidence() {
        val journey = journeyWithConnection()
        assertTrue(journey.transfers.isNotEmpty())
        val report = service.evaluate(journey, emptyList(), emptyList(), now)
        assertTrue(report.journey.transfers.all { it.rationale != null })
    }

    @Test
    fun lowConfidenceProducesWarning() {
        val journey = journeyWithConnection()
        val canceled = journey.transfers.first().let { connection ->
            connection.copy(
                departingLeg = connection.departingLeg.copy(realtimeStatus = RealtimeStatus.CANCELED),
            )
        }
        val report = service.evaluate(journey.copy(transfers = listOf(canceled)), emptyList(), emptyList(), now)
        assertEquals(TransferConfidence.LOW, report.worstConfidence)
        assertTrue(report.warnings.isNotEmpty())
    }

    @Test
    fun journeysWithoutTransfersReportNullConfidence() {
        val journey = journeyWithConnection().copy(transfers = emptyList())
        val report = service.evaluate(journey, emptyList(), emptyList(), now)
        assertNull(report.worstConfidence)
        assertTrue(report.warnings.isEmpty())
    }
}