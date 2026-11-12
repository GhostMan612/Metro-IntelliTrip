package com.intellitrip.routing

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.RouteKey
import com.intellitrip.domain.Stop
import com.intellitrip.domain.StopKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** OTP's identifiers are namespaced and sometimes only names; the bridge must be strict. */
class OtpIdentityResolverTest {

    private val feedId: FeedId = RoutingFixtures.feedId
    private val resolver = OtpIdentityResolver(RoutingFixtures.network)

    @Test
    fun exactAndNamespacedStopIdsResolveToLocalKeys() {
        assertEquals(
            StopKey(feedId, "S1"),
            resolver.resolveStop("S1", null, null, feedId),
        )
        assertEquals(
            StopKey(feedId, "S2"),
            resolver.resolveStop("$feedId:S2", null, null, feedId),
        )
        assertEquals(
            StopKey(feedId, "S3"),
            resolver.resolveStop("1_S3", null, null, feedId),
        )
    }

    @Test
    fun uniqueStopNameResolvesWithoutAPoint() {
        assertEquals(
            StopKey(feedId, "S2"),
            resolver.resolveStop(null, "Second", null, feedId),
        )
    }

    @Test
    fun ambiguousStopNameNeedsAPointAndAcceptsOnlyANearbyMatch() {
        val duplicate = RoutingNetwork(
            feedId = feedId,
            metadata = RoutingFixtures.network.metadata,
            routes = RoutingFixtures.network.routes,
            stops = RoutingFixtures.network.stops + Stop(
                key = StopKey(feedId, "S2-other"),
                code = "S2-other",
                name = "Second",
                description = null,
                location = GeoPoint(45.5, -93.0),
                url = null,
                locationType = 0,
                parentStation = null,
                zoneId = null,
            ),
            trips = RoutingFixtures.network.trips,
        )
        val ambiguous = OtpIdentityResolver(duplicate)

        assertNull(ambiguous.resolveStop(null, "Second", null, feedId))
        assertEquals(
            StopKey(feedId, "S2"),
            ambiguous.resolveStop(null, "Second", GeoPoint(44.9860, -93.2650), feedId),
        )
        // A point near the duplicate resolves that one, not the original.
        assertEquals(
            StopKey(feedId, "S2-other"),
            ambiguous.resolveStop(null, "Second", GeoPoint(45.5, -93.0), feedId),
        )
        // A point near neither is not a guess.
        assertNull(ambiguous.resolveStop(null, "Second", GeoPoint(44.0, -92.0), feedId))
    }

    @Test
    fun routeResolvesByIdShortNameAndLongName() {
        assertEquals(
            RouteKey(feedId, "902"),
            resolver.resolveRoute("902", null, null, feedId),
        )
        assertEquals(
            RouteKey(feedId, "901"),
            resolver.resolveRoute(null, "A", null, feedId),
        )
        assertEquals(
            RouteKey(feedId, "901"),
            resolver.resolveRoute(null, null, "Blue Line", feedId),
        )
        assertEquals(
            RouteKey(feedId, "903"),
            resolver.resolveRoute("$feedId:903", null, null, feedId),
        )
    }

    @Test
    fun unknownRouteIsNotGuessed() {
        assertNull(resolver.resolveRoute("does-not-exist", "ZZ", "No Such Route", feedId))
    }

    @Test
    fun tripResolvesOnlyWhenItsRouteMatches() {
        assertEquals(
            RoutingFixtures.tripA1.key,
            resolver.resolveTrip("T1", RouteKey(feedId, "901"), feedId),
        )
        assertEquals(
            RoutingFixtures.tripA1.key,
            resolver.resolveTrip("$feedId:T1", null, feedId),
        )
        // T1 is on route 901, not 902. Attaching it to the wrong route would make
        // the selected journey render the wrong shape.
        assertNull(resolver.resolveTrip("T1", RouteKey(feedId, "902"), feedId))
        assertNull(resolver.resolveTrip("T-unknown", null, feedId))
    }

    @Test
    fun feedScopedStopIdsAreNotReturnedForAnotherFeed() {
        val otherFeed = FeedId("other-feed")
        assertNull(resolver.resolveStop("S1", null, null, otherFeed))
    }
}
