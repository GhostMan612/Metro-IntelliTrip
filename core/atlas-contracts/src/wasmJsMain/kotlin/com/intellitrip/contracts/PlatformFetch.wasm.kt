package com.intellitrip.contracts

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.JsAny
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

private const val MAX_RESPONSE_BYTES = 256 * 1024 * 1024
private const val MAX_CHUNK_BYTES = 1024 * 1024

private var nextFetchId = 0

private class PendingResponse(val continuation: CancellableContinuation<PlatformHttpResponse>) {
    var status: Int = 0
    var headers: Map<String, String> = emptyMap()
    var expectedLength: Int = -1
    var received: Int = 0
    val chunks = mutableListOf<ByteArray>()
}

private val pendingResponses = mutableMapOf<Int, PendingResponse>()

/**
 * Browser `fetch` bridge.
 *
 * A whole GTFS ZIP cannot cross the wasm/JS boundary as a JS array of numbers
 * (V8 allocates a boxed double per element). The response is streamed in
 * 64 KiB chunks, each copied straight into a Kotlin [ByteArray] at completion.
 * Only the metadata callback is exported; the JS side never calls back into
 * Kotlin with a typed array.
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
fun atlasHttpStart(
    id: Int,
    status: Int,
    etag: String,
    lastModified: String,
    contentType: String,
    length: Int,
) {
    val pending = pendingResponses[id] ?: return
    if (length < 0 || length > MAX_RESPONSE_BYTES) {
        pendingResponses.remove(id)
        pending.continuation.resumeWithException(
            FetchTransportException("HTTP response is too large ($length bytes)"),
        )
        return
    }
    pending.status = status
    pending.expectedLength = length
    pending.headers = buildMap {
        if (etag.isNotEmpty()) put("ETag", etag)
        if (lastModified.isNotEmpty()) put("Last-Modified", lastModified)
        if (contentType.isNotEmpty()) put("Content-Type", contentType)
    }
}

@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
fun atlasHttpChunk(id: Int, payload: JsAny?) {
    val pending = pendingResponses[id] ?: return
    if (payload == null) {
        fail(id, pending, "empty chunk")
        return
    }
    val size = getArrayLength(payload)
    if (size < 0 || size > MAX_CHUNK_BYTES || pending.received + size > pending.expectedLength) {
        fail(id, pending, "invalid chunk length $size")
        return
    }
    val chunk = ByteArray(size)
    for (index in 0 until size) {
        val value = getArrayElement(payload, index).toInt()
        if (value !in 0..255) {
            fail(id, pending, "chunk byte out of range")
            return
        }
        chunk[index] = value.toByte()
    }
    pending.chunks += chunk
    pending.received += size
}

@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
fun atlasHttpDone(id: Int) {
    val pending = pendingResponses.remove(id) ?: return
    if (pending.received != pending.expectedLength) {
        pending.continuation.resumeWithException(
            FetchTransportException(
                "HTTP response truncated: expected ${pending.expectedLength} bytes, received ${pending.received}",
            ),
        )
        return
    }
    val body = ByteArray(pending.expectedLength)
    var offset = 0
    for (chunk in pending.chunks) {
        chunk.copyInto(body, offset)
        offset += chunk.size
    }
    pending.continuation.resume(
        PlatformHttpResponse(status = pending.status, body = body, headers = pending.headers),
    )
}

@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
fun atlasHttpError(id: Int, message: String) {
    val pending = pendingResponses.remove(id) ?: return
    pending.continuation.resumeWithException(FetchTransportException("fetch failed: $message"))
}

private fun fail(id: Int, pending: PendingResponse, reason: String) {
    pendingResponses.remove(id)
    pending.continuation.resumeWithException(FetchTransportException("fetch failed: $reason"))
}

private fun getArrayLength(arr: JsAny): Int = js("""arr.length""")

private fun getArrayElement(arr: JsAny, index: Int): Double = js("""arr[index]""")

private fun jsonEscape(value: String): String = buildString {
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) {
                append("\\u")
                append(ch.code.toString(16).padStart(4, '0'))
            } else {
                append(ch)
            }
        }
    }
}

private fun headerJson(headers: Map<String, String>): String =
    headers.entries
        // User-Agent is a forbidden browser request header; sending it from
        // fetch is silently ignored or rejected depending on the browser.
        .filterNot { it.key.equals("User-Agent", ignoreCase = true) }
        .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
            "\"${jsonEscape(key)}\":\"${jsonEscape(value)}\""
        }

private fun startFetch(id: Int, url: String, headersJson: String): JsAny? =
    js(
        """(() => {
        fetch(url, { headers: JSON.parse(headersJson) }).then(async response => {
            const buffer = await response.arrayBuffer();
            const bytes = new Uint8Array(buffer);
            atlasHttpStart(
                id,
                response.status,
                response.headers.get('ETag') || '',
                response.headers.get('Last-Modified') || '',
                response.headers.get('Content-Type') || '',
                bytes.length
            );
            const chunkSize = 65536;
            for (let offset = 0; offset < bytes.length; offset += chunkSize) {
                atlasHttpChunk(
                    id,
                    Array.from(bytes.subarray(offset, Math.min(offset + chunkSize, bytes.length)))
                );
            }
            atlasHttpDone(id);
        }).catch(error => {
            atlasHttpError(id, String(error && error.message ? error.message : error));
        });
        return null;
    })()"""
    )

actual suspend fun platformFetchResponse(
    url: String,
    headers: Map<String, String>,
): PlatformHttpResponse {
    val id = nextFetchId++
    val encodedHeaders = headerJson(headers)
    return suspendCancellableCoroutine { continuation ->
        pendingResponses[id] = PendingResponse(continuation)
        continuation.invokeOnCancellation { pendingResponses.remove(id) }
        try {
            startFetch(id, url, encodedHeaders)
        } catch (e: Exception) {
            pendingResponses.remove(id)
            continuation.resumeWithException(FetchTransportException("fetch failed: ${e.message}", e))
        }
    }
}

actual suspend fun platformFetch(url: String, headers: Map<String, String>): ByteArray {
    val response = platformFetchResponse(url, headers)
    if (response.status !in 200..299) {
        throw FetchTransportException("GET $url failed: HTTP ${response.status}")
    }
    return response.body
}
