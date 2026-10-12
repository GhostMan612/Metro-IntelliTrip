package com.intellitrip

import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.JourneyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * A plan is only meaningful for the endpoints it was computed from.
 *
 * Showing the previous plan after an endpoint changes would present a route between
 * the old endpoints as though it answered the new question, which is worse than
 * showing nothing at all. These tests pin that behavior.
 */
class PlannerEndpointStateTest {

    private val origin = GeoPoint(44.98, -93.27)
    private val destination = GeoPoint(45.01, -93.18)
    private val elsewhere = GeoPoint(44.90, -93.40)

    private val journey = JourneyOption(
        id = "journey-1",
        legs = emptyList(),
        transfers = emptyList(),
        departure = Instant.parse("2026-10-05T12:00:00Z"),
        arrival = Instant.parse("2026-10-05T12:30:00Z"),
    )

    private fun stateWithPlan() = AppState(
        journeys = listOf(journey),
        journeyWarnings = listOf("walking distance is long"),
        selectedJourneyId = journey.id,
        origin = origin,
        destination = destination,
        status = "1 journeys planned",
    )

    @Test
    fun changingOriginClearsThePlan() {
        val after = stateWithPlan().withEndpoints(origin = elsewhere)
        assertTrue("stale journeys survived an origin change", after.journeys.isEmpty())
        assertTrue(after.journeyWarnings.isEmpty())
        assertNull("a cleared plan must not keep a selection", after.selectedJourneyId)
        assertEquals(elsewhere, after.origin)
        assertEquals(destination, after.destination)
    }

    @Test
    fun changingDestinationClearsThePlan() {
        val after = stateWithPlan().withEndpoints(destination = elsewhere)
        assertTrue(after.journeys.isEmpty())
        assertNull(after.selectedJourneyId)
        assertEquals(origin, after.origin)
        assertEquals(elsewhere, after.destination)
    }

    @Test
    fun swappingClearsThePlanAndExchangesEndpoints() {
        val after = stateWithPlan().withEndpoints(origin = destination, destination = origin)
        assertEquals(destination, after.origin)
        assertEquals(origin, after.destination)
        assertTrue("a swapped plan still described the unswapped trip", after.journeys.isEmpty())
        assertNull(after.selectedJourneyId)
    }

    @Test
    fun reselectingTheSameEndpointKeepsThePlan() {
        val before = stateWithPlan()
        val after = before.withEndpoints(origin = origin, destination = destination)
        assertSame("re-selecting an unchanged endpoint discarded a valid plan", before, after)
        assertEquals(1, after.journeys.size)
        assertEquals(journey.id, after.selectedJourneyId)
    }

    @Test
    fun onlyTheChangedEndpointIsCleared() {
        val before = stateWithPlan()
        val after = before.withEndpoints(origin = elsewhere)
        assertEquals("status belongs to the old plan and is left to the planner", before.status, after.status)
        assertEquals(before.focus, after.focus)
        assertEquals(before.autoRefresh, after.autoRefresh)
    }

    @Test
    fun clearingIsIdempotent() {
        val once = stateWithPlan().withEndpoints(destination = elsewhere)
        val twice = once.withEndpoints(destination = elsewhere)
        assertSame(twice, once.withEndpoints(destination = elsewhere))
        assertTrue(twice.journeys.isEmpty())
    }

    @Test
    fun aDefaultStateHasNoPlan() {
        val fresh = AppState()
        assertTrue(fresh.journeys.isEmpty())
        assertNull(fresh.selectedJourneyId)
    }

    @Test
    fun planningDurationIsUnaffectedByEndpointClearing() {
        // Guards against the clear path accidentally touching unrelated fields.
        val after = stateWithPlan().withEndpoints(origin = elsewhere)
        assertEquals(Duration.ofMinutes(30), journey.duration)
    }

    // --- which journey is drawn -----------------------------------------------

    private val later = journey.copy(
        id = "journey-2",
        arrival = Instant.parse("2026-10-05T13:00:00Z"),
    )

    @Test
    fun earliestArrivalIsDrawnWhenNothingIsSelected() {
        val state = AppState(journeys = listOf(later, journey), selectedJourneyId = null)
        assertEquals(journey.id, state.activeJourneyId())
    }

    @Test
    fun explicitSelectionWinsOverEarliestArrival() {
        val state = AppState(journeys = listOf(journey, later), selectedJourneyId = later.id)
        assertEquals(later.id, state.activeJourneyId())
    }

    @Test
    fun aSelectionThatNoLongerExistsFallsBack() {
        val state = AppState(journeys = listOf(journey, later), selectedJourneyId = "removed")
        assertEquals(journey.id, state.activeJourneyId())
    }

    @Test
    fun noPlanDrawsNothing() {
        assertNull(AppState().activeJourneyId())
        assertNull(AppState(journeys = emptyList()).activeJourney())
    }

    @Test
    fun highlightedCardAndDrawnRouteAgree() {
        // MapChrome highlights with activeJourneyId and the map draws activeJourney.
        val state = AppState(journeys = listOf(later, journey), selectedJourneyId = later.id)
        assertEquals(state.activeJourneyId(), state.activeJourney()?.id)
        val cleared = state.withEndpoints(destination = elsewhere)
        assertNull(cleared.activeJourneyId())
    }
}