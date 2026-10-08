package com.intellitrip.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class IdentityTest {
    @Test
    fun agencyIdRejectsBlank() {
        assertFailsWith<IllegalArgumentException> { AgencyId("") }
    }

    @Test
    fun namespacedIdsSeparateSameRawIdAcrossAgencies() {
        val a = RouteKey(AgencyId("feed-a/1"), "1")
        val b = RouteKey(AgencyId("feed-b/1"), "1")
        assertNotEquals(a, b)
    }

    @Test
    fun sameAgencyAndRouteAreEqual() {
        val key = RouteKey(AgencyId("metro"), "901")
        assertEquals(RouteKey(AgencyId("metro"), "901"), key)
    }
}
