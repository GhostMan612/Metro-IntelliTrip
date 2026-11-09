package com.intellitrip.contracts

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.JsAny
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

private var nextFetchId = 0
private val pendingFetches = mutableMapOf<Int, CancellableContinuation<ByteArray>>()

/**
 * Called from JS when a `fetch` settles. Payload is a plain JS array of
 * numbers (converted from `Uint8Array`) on success, error text on failure.
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
fun atlasFetchDone(id: Int, ok: Boolean, payload: JsAny?) {
    val cont = pendingFetches.remove(id) ?: return
    if (ok && payload != null) {
        val size = getArrayLength(payload)
        val bytes = ByteArray(size)
        for (i in 0 until size) {
            bytes[i] = getArrayElement(payload, i).toInt().toByte()
        }
        cont.resume(bytes)
    } else {
        cont.resumeWithException(FetchTransportException("fetch failed: ${payload?.toString() ?: "unknown error"}"))
    }
}

private fun getArrayLength(arr: JsAny): Int = js("""arr.length""")

private fun getArrayElement(arr: JsAny, index: Int): Double = js("""arr[index]""")

private fun headerJson(headers: Map<String, String>): String =
    headers.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
        "\"$k\": \"$v\""
    }

private fun startFetch(id: Int, url: String, headersJson: String): JsAny =
    js(
        """fetch(url, { headers: JSON.parse(headersJson) }).then(r => {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.arrayBuffer();
    }).then(buf => {
        atlasFetchDone(id, true, Array.from(new Uint8Array(buf)));
    }).catch(err => {
        atlasFetchDone(id, false, String(err && err.message ? err.message : err));
    })"""
    )

actual suspend fun platformFetch(url: String, headers: Map<String, String>): ByteArray {
    val id = nextFetchId++
    val obj = headerJson(headers)
    return suspendCancellableCoroutine { cont ->
        pendingFetches[id] = cont
        startFetch(id, url, obj)
    }
}
