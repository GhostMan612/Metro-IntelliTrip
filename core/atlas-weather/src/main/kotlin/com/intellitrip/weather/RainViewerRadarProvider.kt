package com.intellitrip.weather

import com.intellitrip.contracts.ProviderError
import com.intellitrip.contracts.ProviderResult
import com.intellitrip.contracts.RadarProvider
import com.intellitrip.domain.RadarFrame
import com.intellitrip.domain.RadarFrameKind
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Radar imagery adapter for the RainViewer weather-maps API.
 *
 * PROVISIONAL / RESEARCH-GATED (ADR-003): convenient for prototyping, but
 * production suitability depends on terms, operational limits and long-term
 * availability that have not been confirmed. The `RadarProvider` contract keeps
 * NOAA/NWS, MRMS and IEM interchangeable.
 */
class RainViewerRadarProvider(
    private val http: WeatherHttpClient,
    private val context: WeatherRequestContext,
    private val apiBase: String = "https://api.rainviewer.com",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    override val providerId: String = "rainviewer",
) : RadarProvider {

    override suspend fun latestFrame(): ProviderResult<RadarFrame?> {
        val index = withContext(ioDispatcher) { fetchIndex() }
            ?: return failureOr("RainViewer index unavailable")
        val latest = index.frames.lastOrNull()
        return ProviderResult.Success(latest, context.freshness(index.generatedAt))
    }

    override suspend fun frames(since: Instant): ProviderResult<List<RadarFrame>> {
        val index = withContext(ioDispatcher) { fetchIndex() }
            ?: return failureOr("RainViewer index unavailable")
        val selected = index.frames.filter { !it.timestamp.isBefore(since) }
        return ProviderResult.Success(selected, context.freshness(index.generatedAt))
    }

    private fun fetchIndex(): RadarIndex? = try {
        parseIndex(http.get("$apiBase/public/weather-maps.json", context.headers()).toString(Charsets.UTF_8))
    } catch (e: Exception) {
        // Preserve the cause so callers can distinguish a network outage from an
        // unreadable index document.
        lastFailure = e
        null
    }

    private var lastFailure: Exception? = null

    private fun failureOr(error: String): ProviderResult<Nothing> {
        val cause = lastFailure
        lastFailure = null
        return when (cause) {
            is IOException -> ProviderResult.NetworkFailure(ProviderError(error, cause))
            else -> ProviderResult.MalformedResponse(ProviderError(error, cause))
        }
    }

    internal data class RadarIndex(val generatedAt: Instant?, val frames: List<RadarFrame>)

    internal companion object {
        const val ATTRIBUTION = "Radar data by RainViewer"

        fun parseIndex(payload: String): RadarIndex? {
            val root = Json.parse(payload).asObject()
            val host = root["host"].asStringOrNull() ?: return null
            val generated = root["generated"].asLongOrNull()?.let(Instant::ofEpochSecond)
            val radar = root["radar"].asObject()
            // Only observed frames are published. RainViewer's `nowcast` entry is a
            // model forecast, not measurement: labelling it alongside past frames
            // invited riders to read predicted rain as rain that has already fallen.
            // It is also the least stable part of the response, and an unusable or
            // wrong-looking forecast is worse than no forecast. A forecast, if it is
            // ever added, needs its own provider, its own layer, and wording that says
            // it is a prediction.
            val frames = parseFrames(host, radar["past"].asList(), RadarFrameKind.PAST)
                .sortedBy { it.timestamp }
            return RadarIndex(generated, frames)
        }

        private fun parseFrames(
            host: String,
            entries: List<Any?>,
            kind: RadarFrameKind,
        ): List<RadarFrame> = entries.mapNotNull { entry ->
            val obj = entry.asObject()
            val time = obj["time"].asLongOrNull() ?: return@mapNotNull null
            val path = obj["path"].asStringOrNull() ?: return@mapNotNull null
            RadarFrame(
                providerId = "rainviewer",
                timestamp = Instant.ofEpochSecond(time),
                tileUrlTemplate = tileUrl(host, path),
                attribution = ATTRIBUTION,
                kind = kind,
            )
        }

        /**
         * Builds the raster tile URL template.
         *
         * RainViewer's `path` already carries the complete template, ending in the
         * `{z}/{x}/{y}/{color}/{options}.png` placeholders. Appending a second copy of
         * those segments, as an earlier revision did, produced a URL whose only tile
         * request was the one the server cannot answer, so the radar layer loaded and
         * stayed empty. The template is therefore the host joined to the path, with
         * exactly one separator.
         */
        private fun tileUrl(host: String, path: String): String =
            host.trimEnd('/') + "/" + path.trimStart('/')
    }
}