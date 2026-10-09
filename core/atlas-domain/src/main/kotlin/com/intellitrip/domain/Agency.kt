package com.intellitrip.domain

import java.time.ZoneId

data class Agency(
    val id: AgencyId,
    val feedId: FeedId,
    val name: String,
    val url: String?,
    val timezone: ZoneId?,
    val lang: String?,
    val phone: String?,
    val fareUrl: String?,
    val email: String?,
)