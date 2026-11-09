package com.intellitrip.domain

import kotlinx.datetime.TimeZone

data class Agency(
    val id: AgencyId,
    val feedId: FeedId,
    val name: String,
    val url: String?,
    val timezone: TimeZone?,
    val lang: String?,
    val phone: String?,
    val fareUrl: String?,
    val email: String?,
)