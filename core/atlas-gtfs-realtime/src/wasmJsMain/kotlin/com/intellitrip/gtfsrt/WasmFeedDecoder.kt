package com.intellitrip.gtfsrt

/**
 * wasmJs feed decoder.
 *
 * The browser runs [PortableFeedDecoder], a bounded wire reader shared with
 * the JVM parity test. The JVM keeps the generated Java bindings (see
 * `JvmFeedDecoder`); both produce the same portable [RtFeedMessage].
 */
actual fun platformFeedDecoder(): FeedDecoder = PortableFeedDecoder
