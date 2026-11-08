package com.intellitrip.webspike

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

actual fun platformNow(): Instant = Clock.System.now()

fun main() {
    println(describeNow())
}
