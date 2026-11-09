package com.intellitrip.gtfs

import com.intellitrip.domain.StopTime

/**
 * A stop-time sink bound to a staging area. Rows stream straight to the
 * writer's buffer; [close] finalizes the data + index bytes and persists
 * them into the staging area so they can be moved into the activated
 * snapshot.
 */
class StopTimeSinkHandle internal constructor(
    private val writer: StopTimeIndexWriter,
    private val store: PlatformFileStore,
    private val dataPath: String,
    private val indexPath: String,
) : StopTimeSink {

    override fun accept(stopTime: StopTime) {
        writer.write(stopTime)
    }

    fun close() {
        val (dataBytes, indexBytes) = writer.finish()
        store.writeBytes(dataPath, dataBytes)
        store.writeBytes(indexPath, indexBytes)
    }

    fun stagedDataPath(): String = dataPath
}
