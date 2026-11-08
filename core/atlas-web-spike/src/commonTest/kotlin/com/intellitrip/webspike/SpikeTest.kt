package com.intellitrip.webspike

import kotlin.test.Test
import kotlin.test.assertTrue

class SpikeTest {
    @Test
    fun describeNowMentionsDate() {
        assertTrue(describeNow().contains("2026-11-08"))
    }
}
