package com.intellitrip.offline

import kotlin.test.Test
import kotlin.test.assertEquals

class BasemapPolicyTest {

    @Test
    fun onlineOnlyWhenNoPackageInstalled() {
        assertEquals(BasemapPreference.Online, BasemapPolicy(onlineEnabled = true).preference())
    }

    @Test
    fun offlinePackagePreferredWhenInstalled() {
        assertEquals(
            BasemapPreference.OfflinePackage,
            BasemapPolicy(onlineEnabled = true, offlineInstalled = true).preference(),
        )
    }

    @Test
    fun noBasemapWhenOfflineOnlyAndMissing() {
        assertEquals(
            BasemapPreference.None,
            BasemapPolicy(onlineEnabled = false, offlineInstalled = false).preference(),
        )
    }
}
