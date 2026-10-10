package com.intellitrip.gtfs

import com.intellitrip.domain.StopTime
import java.nio.file.Path

/**
 * A stop-time sink bound to a staging area. Rows stream straight to disk; [close]
 * finalizes the index so it can be moved into the activated snapshot.
 */
class StopTimeSinkHandle internal constructor(
    private val writer: StopTimeIndexWriter,
    private val dataPath: Path,
    private val indexPath: Path,
) : AutoCloseable, StopTimeSink {

    override fun accept(stopTime: StopTime) {
        writer.write(stopTime)
    }

    override fun close() {
        writer.finish(indexPath)
    }

    fun stagedDataPath(): Path = dataPath
}