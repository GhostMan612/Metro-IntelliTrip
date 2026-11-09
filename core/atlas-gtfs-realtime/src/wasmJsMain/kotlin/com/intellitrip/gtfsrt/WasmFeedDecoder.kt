package com.intellitrip.gtfsrt

/**
 * wasmJs feed decoder.
 *
 * There is no protobuf runtime on wasmJs yet (Java protobuf is JVM-only and
 * `kotlinx-serialization-protobuf` was not adopted: the implementation uses
 * the Java bindings). Until a wasmJs wire parser lands, decoding fails as a
 * malformed payload rather than pretending to succeed. The mapper, polling
 * client and HTTP seam are fully portable; only this actual is a stub.
 */
actual fun platformFeedDecoder(): FeedDecoder = FeedDecoder {
    throw GtfsRealtimeException("GTFS-Realtime protobuf decoding is not available on wasmJs yet")
}
