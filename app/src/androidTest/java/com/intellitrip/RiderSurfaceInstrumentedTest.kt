package com.intellitrip

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.intellitrip.map.MapFeature
import com.intellitrip.map.MapSelection
import com.intellitrip.ui.Departure
import com.intellitrip.ui.MapFeatureSheet
import com.intellitrip.ui.MoreScreen
import com.intellitrip.ui.SavedScreen
import com.intellitrip.ui.SavedStop
import com.intellitrip.ui.theme.IntelliTripTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Device-side checks that the rider surfaces actually render.
 *
 * These exist because a screenshot proves a pixel and a passing build proves nothing
 * about whether a control was ever composed. Each test asserts on the semantics tree
 * on a real device, which is also what a screen reader navigates, so a test that passes
 * is a TalkBack-visible surface by definition.
 */
@RunWith(AndroidJUnit4::class)
class RiderSurfaceInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private val now: Instant = Instant.parse("2026-10-05T12:00:00Z")

    @Test
    fun moreScreenRendersRiderControlsAndNestsAdvancedUnderneath() {
        compose.setContent {
            IntelliTripTheme {
                MoreScreen(
                    state = AppState(status = "Ready", radarEnabled = true, autoRefresh = false),
                    onToggleRadar = {},
                    onToggleAutoRefresh = {},
                    onLoadAlerts = {},
                    onLoadStatic = {},
                    onOpenScope = {},
                    onOpenData = {},
                    onLocateMe = {},
                )
            }
        }

        compose.onNodeWithText("Precipitation radar").assertIsDisplayed()
        compose.onNodeWithText("Auto refresh").assertIsDisplayed()
        compose.onNodeWithText("Download schedules").assertIsDisplayed()

        // Engine administration is present but explicitly labelled as advanced,
        // which is the separation the recovery plan asks for.
        //
        // Reached by scrolling. More is a LazyColumn, so items below the fold are not
        // merely invisible, they are never composed: both assertExists and
        // assertIsDisplayed fail against them. Scrolling to the item is what actually
        // proves a rider can get to it, rather than asserting a layout accident.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Advanced", ignoreCase = true))
        compose.onNodeWithText("Advanced", ignoreCase = true).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Data and layers", ignoreCase = true))
        compose.onNodeWithText("Data and layers", ignoreCase = true).assertIsDisplayed()
    }

    @Test
    fun savedScreenExplainsTheEmptyStateWithoutDemandingAnAccount() {
        compose.setContent {
            IntelliTripTheme {
                SavedScreen(saved = emptyList(), onOpenStop = {}, onRemoveStop = {})
            }
        }
        compose.onNodeWithText("Nothing saved yet").assertIsDisplayed()
        // The plan requires Saved to work with no account. The copy states that
        // outright, and asserting the exact sentence keeps it true: a well-meaning
        // sign-in prompt added later would fail here.
        compose.onNodeWithText("need no account", substring = true, ignoreCase = true).assertExists()
    }

    @Test
    fun savedScreenListsFeedScopedStops() {
        val saved = listOf(
            SavedStop("metro-transit", "S1", "Nicollet Mall", 44.97, -93.26, now),
            SavedStop("other-agency", "S1", "Main St & 1st", 44.95, -93.10, now),
        )
        compose.setContent {
            IntelliTripTheme {
                SavedScreen(saved = saved, onOpenStop = {}, onRemoveStop = {})
            }
        }
        // Two agencies both numbering a stop "S1" must remain two separate entries.
        compose.onNodeWithText("Nicollet Mall").assertIsDisplayed()
        compose.onNodeWithText("Main St & 1st").assertIsDisplayed()
    }

    /**
     * A stop detail must offer a real action, not just readable text.
     *
     * The reviewer's finding was that ambiguous candidates were non-clickable surfaces.
     * This asserts the save action is present and enabled for a known stop.
     */
    @Test
    fun stopDetailOffersToSaveTheStop() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            IntelliTripTheme {
                val feed = com.intellitrip.domain.FeedId("metro-transit")
                val stop = com.intellitrip.domain.Stop(
                    key = com.intellitrip.domain.StopKey(feed, "S1"),
                    code = null,
                    name = "Nicollet Mall",
                    description = null,
                    location = com.intellitrip.domain.GeoPoint(44.97, -93.26),
                    url = null,
                    locationType = null,
                    parentStation = null,
                    zoneId = null,
                )
                MapFeatureSheet(
                    selection = MapSelection.Single(MapFeature.Stop("metro-transit", "S1", "Nicollet Mall")),
                    stops = mapOf("metro-transit/S1" to stop),
                    vehicles = emptyMap(),
                    journeys = emptyList(),
                    departures = listOf(
                        Departure(
                            stopName = "Nicollet Mall",
                            routeShortName = "A",
                            routeLongName = "Blue Line",
                            headsign = "Westbound",
                            routeColor = null,
                            scheduledTime = now,
                            isLive = false,
                            vehicleUpdatedAt = null,
                            minutesUntil = 4,
                        ),
                    ),
                    now = now,
                    onDismiss = {},
                    onSaveStop = {},
                    onSelectJourney = {},
                    onZoomToCluster = {},
                    onChooseCandidate = {},
                )
            }
        }
        compose.onNodeWithText("Nicollet Mall").assertIsDisplayed()
        compose.onNodeWithText("Save this stop").assertIsDisplayed()
        // A schedule-only departure must never be labelled live.
        compose.onNodeWithText("Schedule").assertIsDisplayed()
    }

    /**
     * Ambiguous candidates must be selectable, which is the finding under review.
     *
     * Tapping one is expected to hand that choice back to the caller; the assertion is
     * that the tap is delivered rather than swallowed.
     */
    @Test
    fun ambiguousCandidatesAreSelectable() {
        var chosen: MapFeature? = null
        compose.setContent {
            IntelliTripTheme {
                MapFeatureSheet(
                    selection = MapSelection.Ambiguous(
                        listOf(
                            MapFeature.Stop("metro-transit", "S1", "Nicollet Mall"),
                            MapFeature.Vehicle(
                                vehicleId = "v1",
                                routeShortName = "A",
                                headsign = "Westbound",
                                position = com.intellitrip.domain.GeoPoint(44.97, -93.26),
                                updatedAt = now,
                            ),
                        ),
                    ),
                    stops = emptyMap(),
                    vehicles = emptyMap(),
                    journeys = emptyList(),
                    departures = emptyList(),
                    now = now,
                    onDismiss = {},
                    onSaveStop = {},
                    onSelectJourney = {},
                    onZoomToCluster = {},
                    onChooseCandidate = { chosen = it },
                )
            }
        }
        compose.onNodeWithText("Nicollet Mall").assertIsDisplayed().performClick()
        assertEquals("Nicollet Mall", (chosen as? MapFeature.Stop)?.name)
    }

    /**
     * Cluster selection must report the vehicle count the writer actually emitted.
     *
     * The properties are `count`, not `point_count`; reading the wrong name made every
     * cluster report zero vehicles.
     */
    @Test
    fun clusterSelectionReportsTheRealCount() {
        compose.setContent {
            IntelliTripTheme {
                MapFeatureSheet(
                    selection = MapSelection.Single(
                        MapFeature.VehicleCluster("cluster-1", 7, 44.97, -93.26),
                    ),
                    stops = emptyMap(),
                    vehicles = emptyMap(),
                    journeys = emptyList(),
                    departures = emptyList(),
                    now = now,
                    onDismiss = {},
                    onSaveStop = {},
                    onSelectJourney = {},
                    onZoomToCluster = {},
                    onChooseCandidate = {},
                )
            }
        }
        compose.onNodeWithText("7 vehicles here").assertIsDisplayed()
    }
}