package com.intellitrip

import android.database.sqlite.SQLiteDatabase
import com.intellitrip.offline.BasemapArchiveFormat
import com.intellitrip.offline.BasemapPackage
import com.intellitrip.offline.MbtilesMetadata
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

internal class MbtilesTileServer : AutoCloseable {
    private data class Archive(val path: String, val metadata: MbtilesMetadata)

    private val lock = Any()
    private val archives = mutableMapOf<String, Archive>()
    private val tokensByPackage = mutableMapOf<String, String>()
    private val workers = ThreadPoolExecutor(
        4,
        4,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(64),
        ThreadFactory { runnable -> Thread(runnable, "mbtiles-http").apply { isDaemon = true } },
    )
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun tileJsonUrl(pkg: BasemapPackage): String? {
        if (pkg.archiveFormat != BasemapArchiveFormat.MBTILES) return null
        val metadata = when (val result = MbtilesValidator.validate(java.io.File(pkg.tilePath))) {
            is MbtilesValidator.Result.Invalid -> return null
            is MbtilesValidator.Result.Valid -> result.metadata
        }
        return synchronized(lock) {
            val socket = try {
                server ?: ServerSocket(0, 32, InetAddress.getByName(LOOPBACK)).also {
                    it.reuseAddress = false
                    server = it
                    acceptThread = Thread({ accept(it) }, "mbtiles-accept").apply {
                        isDaemon = true
                        start()
                    }
                }
            } catch (_: Exception) {
                return@synchronized null
            }
            val token = tokensByPackage.getOrPut(pkg.id) { UUID.randomUUID().toString().replace("-", "") }
            archives[token] = Archive(pkg.tilePath, metadata)
            "http://$LOOPBACK:${socket.localPort}/$token/tilejson.json"
        }
    }

    fun forgetPackage(id: String) {
        synchronized(lock) {
            tokensByPackage.remove(id)?.let(archives::remove)
        }
    }

    private fun accept(socket: ServerSocket) {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                try {
                    workers.execute { client.use(::handle) }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    client.close()
                }
            } catch (_: java.net.SocketException) {
                if (!socket.isClosed) continue
            } catch (_: Exception) {
                if (socket.isClosed) return
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val request = readRequest(input) ?: return
        val response = try {
            route(request)
        } catch (_: Exception) {
            Response(500, "Internal Server Error", "text/plain", ByteArray(0))
        }
        output.write(
            ("HTTP/1.1 ${response.status} ${response.reason}\r\n" +
                "Content-Type: ${response.contentType}\r\n" +
                "Content-Length: ${response.body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n" +
                (if (response.gzip) "Content-Encoding: gzip\r\n" else "") +
                "\r\n").toByteArray(StandardCharsets.US_ASCII),
        )
        output.write(response.body)
        output.flush()
    }

    private fun route(request: String): Response {
        val parts = request.removePrefix("/").split('/')
        if (parts.size < 2) return NOT_FOUND
        val archive = synchronized(lock) { archives[parts[0]] } ?: return NOT_FOUND
        if (parts.size == 2 && parts[1] == "tilejson.json") {
            val bounds = archive.metadata.bounds
            val tileFormat = archive.metadata.tileFormat
            val tileJson = """{"tilejson":"2.2.0","scheme":"xyz","minzoom":${archive.metadata.minZoom},"maxzoom":${archive.metadata.maxZoom},"bounds":[${bounds.minLon},${bounds.minLat},${bounds.maxLon},${bounds.maxLat}],"tiles":["http://$LOOPBACK:${server?.localPort}/${parts[0]}/{z}/{x}/{y}.$tileFormat"]}"""
            return Response(200, "OK", "application/json; charset=utf-8", tileJson.toByteArray(StandardCharsets.UTF_8))
        }
        if (parts.size != 4) return NOT_FOUND
        val z = parts[1].toIntOrNull() ?: return BAD_REQUEST
        val x = parts[2].toIntOrNull() ?: return BAD_REQUEST
        val y = parts[3].substringBefore('.').toIntOrNull() ?: return BAD_REQUEST
        if (parts[3].substringAfter('.', "") != archive.metadata.tileFormat ||
            z !in archive.metadata.minZoom..archive.metadata.maxZoom ||
            z !in 0..24
        ) return NOT_FOUND
        val tileCount = 1L shl z
        if (x < 0 || x.toLong() >= tileCount || y < 0 || y.toLong() >= tileCount) {
            return BAD_REQUEST
        }
        val tmsRow = tileCount - 1L - y
        val tile = SQLiteDatabase.openDatabase(
            archive.path,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        ).use { database ->
            database.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ? LIMIT 1",
                arrayOf(z.toString(), x.toString(), tmsRow.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getBlob(0) else null
            }
        } ?: return NOT_FOUND
        val gzip = tile.isGzip()
        return Response(200, "OK", contentType(archive.metadata.tileFormat), tile, gzip)
    }

    private fun readRequest(input: BufferedInputStream): String? {
        val bytes = ByteArray(MAX_HEADER_BYTES)
        var count = 0
        var ending = 0
        while (count < bytes.size) {
            val byte = input.read()
            if (byte < 0) return null
            bytes[count++] = byte.toByte()
            ending = when {
                ending == 0 && byte == '\r'.code -> 1
                ending == 1 && byte == '\n'.code -> 2
                ending == 2 && byte == '\r'.code -> 3
                ending == 3 && byte == '\n'.code -> 4
                byte == '\r'.code -> 1
                else -> 0
            }
            if (ending == 4) break
        }
        if (ending != 4) return null
        val header = bytes.copyOf(count).toString(StandardCharsets.US_ASCII)
        val line = header.substringBefore("\r\n")
        val fields = line.split(' ')
        if (fields.size != 3 || fields[0] != "GET" || fields[2] != "HTTP/1.1") {
            return null
        }
        val target = fields[1]
        if (!target.startsWith('/') || target.contains("..") || target.contains('\\') ||
            target.contains('%') || target.contains('?') || target.contains('#')
        ) return null
        return target
    }

    private fun ByteArray.isGzip(): Boolean =
        size >= 2 && this[0] == 0x1F.toByte() && this[1] == 0x8B.toByte()

    private fun contentType(format: String): String = when (format) {
        "pbf" -> "application/x-protobuf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    override fun close() {
        synchronized(lock) {
            archives.clear()
            tokensByPackage.clear()
            server?.close()
            server = null
        }
        workers.shutdownNow()
    }

    private data class Response(
        val status: Int,
        val reason: String,
        val contentType: String,
        val body: ByteArray,
        val gzip: Boolean = false,
    )

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val MAX_HEADER_BYTES = 8192
        const val SOCKET_TIMEOUT_MS = 5000

        val BAD_REQUEST = Response(400, "Bad Request", "text/plain", ByteArray(0))
        val NOT_FOUND = Response(404, "Not Found", "text/plain", ByteArray(0))
    }
}
