package com.intellitrip.offline

/**
 * Validation and archive binding for a user-supplied MapLibre style.
 *
 * A tile archive without a style renders nothing, so the two are imported
 * together. The style is untrusted input: it must be a JSON object (not a style
 * URL), it must declare style version 8, and it must reference the selected
 * archive format. Requiring an archive reference ties the style to the archive the rider
 * also selected; a style that only points at remote raster tiles would install,
 * then keep drawing the online basemap while claiming to be offline.
 *
 * The stored style keeps its `pmtiles://` or `mbtiles://` marker. At render
 * time PMTiles is bound to its file URI and MBTiles to the app's loopback
 * TileJSON endpoint.
 */
internal object BasemapStyleValidator {

    const val MAX_STYLE_BYTES: Int = 2 * 1024 * 1024

    sealed interface Result {
        data object Valid : Result

        data class Invalid(val reason: String) : Result
    }

    fun validate(
        styleJson: String,
        format: BasemapArchiveFormat = BasemapArchiveFormat.PMTILES,
    ): Result {
        if (styleJson.isBlank()) {
            return Result.Invalid("package has no style; tiles alone cannot render")
        }
        val bytes = styleJson.encodeToByteArray()
        if (bytes.size > MAX_STYLE_BYTES) {
            return Result.Invalid(
                "style is ${bytes.size} bytes; the maximum is $MAX_STYLE_BYTES bytes",
            )
        }
        if (styleJson.contains('\u0000')) {
            return Result.Invalid("style contains a NUL byte")
        }
        val normalized = normalizeEscapedSlashes(styleJson)
        if (!normalized.trimStart().startsWith("{")) {
            return Result.Invalid("style must be a JSON object, not a URL or a list")
        }
        if (!isStructurallyJson(normalized)) {
            return Result.Invalid("style is not valid JSON: unbalanced strings, braces or brackets")
        }
        if (!VERSION_8.containsMatchIn(normalized)) {
            return Result.Invalid("style must declare \"version\": 8")
        }
        if (!SOURCES_KEY.containsMatchIn(normalized)) {
            return Result.Invalid("style has no \"sources\" object")
        }
        if (!LAYERS_KEY.containsMatchIn(normalized)) {
            return Result.Invalid("style has no \"layers\" array")
        }
        val marker = when (format) {
            BasemapArchiveFormat.PMTILES -> PMTILES_PREFIX
            BasemapArchiveFormat.MBTILES -> MBTILES_PREFIX
        }
        if (!normalized.contains(marker)) {
            return Result.Invalid(
                "style must reference the offline archive with a $marker source URL",
            )
        }
        if (format == BasemapArchiveFormat.MBTILES && REMOTE_RESOURCE.containsMatchIn(normalized)) {
            return Result.Invalid("MBTiles styles must not contain remote HTTP(S) resources")
        }
        return Result.Valid
    }

    /**
     * Replaces every `pmtiles://...` source URL with `pmtiles://<archiveUri>`.
     *
     * MapLibre Native requires the complete path inside the prefix (it does not
     * resolve relative URLs against the style, unlike MapLibre GL JS). The scan
     * stops at JSON string terminators and at structural whitespace, so the rest
     * of the document is preserved byte for byte.
     */
    fun bindArchive(
        styleJson: String,
        archiveUri: String,
        format: BasemapArchiveFormat = BasemapArchiveFormat.PMTILES,
    ): String {
        val normalized = normalizeEscapedSlashes(styleJson)
        val marker = when (format) {
            BasemapArchiveFormat.PMTILES -> PMTILES_PREFIX
            BasemapArchiveFormat.MBTILES -> MBTILES_PREFIX
        }
        val out = StringBuilder(normalized.length + archiveUri.length)
        var index = 0
        while (index < normalized.length) {
            val markerIndex = normalized.indexOf(marker, index)
            if (markerIndex < 0) {
                out.append(normalized, index, normalized.length)
                break
            }
            out.append(normalized, index, markerIndex)
            if (format == BasemapArchiveFormat.PMTILES) out.append(PMTILES_PREFIX)
            out.append(archiveUri)
            var end = markerIndex + marker.length
            while (end < normalized.length) {
                val c = normalized[end]
                if (c == '"' || c == '\'' || c == ',' || c == '}' || c == ']' ||
                    c.isWhitespace()
                ) {
                    break
                }
                end++
            }
            index = end
        }
        return out.toString()
    }

    /** JSON allows `\/`; some encoders use it even though it is unnecessary. */
    private fun normalizeEscapedSlashes(styleJson: String): String =
        styleJson.replace("pmtiles:\\/\\/", PMTILES_PREFIX)
            .replace("mbtiles:\\/\\/", MBTILES_PREFIX)
            .replace("\\/", "/")

    /**
     * Minimal structural JSON check: strings and escapes are respected, and
     * braces/brackets must balance outside strings. It does not attempt to
     * validate every comma or number; MapLibre performs the final syntax check
     * and an invalid style is still an explicit rejection rather than a silent
     * fallback.
     */
    private fun isStructurallyJson(text: String): Boolean {
        var braces = 0
        var brackets = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> braces++
                '}' -> {
                    braces--
                    if (braces < 0) return false
                }
                '[' -> brackets++
                ']' -> {
                    brackets--
                    if (brackets < 0) return false
                }
            }
        }
        return !inString && !escaped && braces == 0 && brackets == 0
    }

    private const val PMTILES_PREFIX = "pmtiles://"
    private const val MBTILES_PREFIX = "mbtiles://"
    private val VERSION_8 = Regex("\"version\"\\s*:\\s*8\\b")
    private val SOURCES_KEY = Regex("\"sources\"\\s*:")
    private val LAYERS_KEY = Regex("\"layers\"\\s*:")
    private val REMOTE_RESOURCE = Regex(
        "\"(?:url|tiles|sprite|glyphs)\"\\s*:\\s*(?:\"[^\"]*?https?://|\\[[^\\]]*?https?://)",
        RegexOption.IGNORE_CASE,
    )
}
