package com.intellitrip.webspike

/**
 * `@js-joda/timezone` is a side-effect module: importing it registers the IANA
 * timezone rules with the js-joda `ZoneId` implementation that kotlinx-datetime
 * uses on Kotlin/Wasm.
 *
 * Without this import `TimeZone.of("America/Chicago")` throws during
 * `WebProduction` initialization and the whole shell fails to start.
 */
@JsModule("@js-joda/timezone")
external object JsJodaTimezone

internal fun installJsJodaTimezone() {
    // Referencing the external declaration keeps the module import in the
    // generated bundle; the module has no API we need to call.
    JsJodaTimezone
}
