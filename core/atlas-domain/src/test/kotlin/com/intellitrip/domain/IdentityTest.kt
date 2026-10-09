package com.intellitrip.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class IdentityTest {
    @Test
    fun idsRejectBlankValues() {
        assertFailsWith<IllegalArgumentException> { AgencyId("") }
        assertFailsWith<IllegalArgumentException> { FeedId("") }
        assertFailsWith<IllegalArgumentException> { RouteKey(FeedId("f"), "") }
    }

    @Test
    fun identicalRouteIdsInSeparateFeedsDoNotCollide() {
        val a = RouteKey(FeedId("feed-a"), "10")
        val b = RouteKey(FeedId("feed-b"), "10")
        assertNotEquals(a, b)
    }

    @Test
    fun identicalStopIdsInSeparateFeedsDoNotCollide() {
        assertNotEquals(StopKey(FeedId("feed-a"), "34"), StopKey(FeedId("feed-b"), "34"))
    }

    @Test
    fun identicalTripIdsInSeparateFeedsDoNotCollide() {
        assertNotEquals(TripKey(FeedId("feed-a"), "338931"), TripKey(FeedId("feed-b"), "338931"))
    }

    @Test
    fun serviceAndShapeIdsDoNotCollideAcrossFeeds() {
        assertNotEquals(ServiceKey(FeedId("feed-a"), "1"), ServiceKey(FeedId("feed-b"), "1"))
        assertNotEquals(ShapeKey(FeedId("feed-a"), "650705003"), ShapeKey(FeedId("feed-b"), "650705003"))
    }

    @Test
    fun sharedStopWithinOneFeedHasSingleIdentity() {
        val stop = StopKey(FeedId("feed-a"), "34")
        assertEquals(stop, StopKey(FeedId("feed-a"), "34"))
    }

    @Test
    fun agencyAssociationDoesNotAffectStopIdentity() {
        val metro = AgencyId("feed-a/0")
        val mapleGrove = AgencyId("feed-a/4")
        val stopKey = StopKey(FeedId("feed-a"), "34")
        val a = Route(RouteKey(FeedId("feed-a"), "10"), metro, "10", null, null, 3, null, null, null, null)
        val b = Route(RouteKey(FeedId("feed-a"), "11"), mapleGrove, "11", null, null, 3, null, null, null, null)
        assertEquals(stopKey, StopKey(FeedId("feed-a"), "34"))
        assertNotEquals(a.agencyId, b.agencyId)
    }

    @Test
    fun feedIdentitySurvivesSnapshotVersionChanges() {
        val feed = FeedId("metro-transit-regional")
        val snapshotA = FeedMetadata(feed, null, null, null, "1790802061", "abc123", java.time.Instant.EPOCH, null, null, null)
        val snapshotB = FeedMetadata(feed, null, null, null, "1790802999", "def456", java.time.Instant.EPOCH.plusSeconds(86400), null, null, null)
        assertEquals(snapshotA.feedId, snapshotB.feedId)
    }
}