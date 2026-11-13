package com.intellitrip.contracts

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.JsAny
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

private var nextIdbId = 0
private val pendingIdb = mutableMapOf<Int, CancellableContinuation<ByteArray?>>()

/**
 * Called from JS when an IndexedDB request settles. Payload is a plain JS
 * array of numbers on success (or null when missing), error text on failure.
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
fun atlasIdbDone(id: Int, ok: Boolean, payload: JsAny?) {
    val cont = pendingIdb.remove(id) ?: return
    if (!ok) {
        cont.resumeWithException(IllegalStateException("IndexedDB failed: ${payload?.toString() ?: "unknown"}"))
        return
    }
    if (payload == null) {
        cont.resumeWith(Result.success(null))
        return
    }
    val size = getArrayLength(payload)
    val bytes = ByteArray(size)
    for (i in 0 until size) bytes[i] = getArrayElement(payload, i).toInt().toByte()
    cont.resume(bytes)
}

private fun getArrayLength(arr: JsAny): Int = js("""arr.length""")

private fun getArrayElement(arr: JsAny, index: Int): Double = js("""arr[index]""")

private fun startIdbPut(id: Int, key: String, hex: String): JsAny =
    js(
        """(() => {
        const dbp = indexedDB.open('atlas-cache', 1);
        const arr = Uint8Array.from(hex.match(/.{1,2}/g) || [], h => parseInt(h, 16));
        dbp.onupgradeneeded = () => {
            const db = dbp.result;
            if (!db.objectStoreNames.contains('files')) db.createObjectStore('files');
        };
        dbp.onsuccess = () => {
            const db = dbp.result;
            const tx = db.transaction('files', 'readwrite');
            tx.objectStore('files').put(arr, key);
            tx.oncomplete = () => {
                db.close();
                atlasIdbDone(id, true, null);
            };
            tx.onabort = tx.onerror = () => {
                const error = tx.error && tx.error.message ? tx.error.message : 'put failed';
                db.close();
                atlasIdbDone(id, false, String(error));
            };
        };
        dbp.onerror = () => atlasIdbDone(id, false, String(dbp.error && dbp.error.message ? dbp.error.message : 'open failed'));
        return dbp;
    })()"""
    )

private fun startIdbGet(id: Int, key: String): JsAny =
    js(
        """(() => {
        const dbp = indexedDB.open('atlas-cache', 1);
        dbp.onupgradeneeded = () => {
            const db = dbp.result;
            if (!db.objectStoreNames.contains('files')) db.createObjectStore('files');
        };
        dbp.onsuccess = () => {
            const db = dbp.result;
            const tx = db.transaction('files', 'readonly');
            const req = tx.objectStore('files').get(key);
            let result = null;
            req.onsuccess = () => {
                const v = req.result;
                if (v !== undefined && v !== null) result = Array.from(v);
            };
            tx.oncomplete = () => {
                db.close();
                atlasIdbDone(id, true, result);
            };
            tx.onabort = tx.onerror = () => {
                const error = tx.error && tx.error.message ? tx.error.message : 'get failed';
                db.close();
                atlasIdbDone(id, false, String(error));
            };
        };
        dbp.onerror = () => atlasIdbDone(id, false, String(dbp.error && dbp.error.message ? dbp.error.message : 'open failed'));
        return dbp;
    })()"""
    )

actual suspend fun platformDurablePut(key: String, bytes: ByteArray) {
    val id = nextIdbId++
    // ByteArray cannot cross the JS boundary; hex is the portable encoding
    // (2x size — acceptable for the durable payload cache). IDs are large but
    // browser IndexedDB handles this natively.
    val hex = bytes.joinToString("") { b ->
        val v = b.toInt() and 0xFF
        val hi = "0123456789abcdef"[v shr 4]
        val lo = "0123456789abcdef"[v and 0x0F]
        "$hi$lo"
    }
    suspendCancellableCoroutine { cont ->
        pendingIdb[id] = cont
        cont.invokeOnCancellation { pendingIdb.remove(id) }
        startIdbPut(id, key, hex)
    }
}

actual suspend fun platformDurableGet(key: String): ByteArray? {
    val id = nextIdbId++
    return suspendCancellableCoroutine { cont ->
        pendingIdb[id] = cont
        cont.invokeOnCancellation { pendingIdb.remove(id) }
        startIdbGet(id, key)
    }
}
