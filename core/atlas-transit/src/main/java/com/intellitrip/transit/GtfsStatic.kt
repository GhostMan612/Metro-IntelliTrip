package com.intellitrip.transit

import java.io.InputStream
import java.util.zip.ZipInputStream

class GtfsStatic private constructor(
    val routes: List<RouteDto>,
    val stops: List<StopDto>,
    val shapesByRoute: Map<String, List<List<ShapePoint>>>,
) {
    companion object {
        fun parse(stream: InputStream): GtfsStatic {
            val files = mutableMapOf<String, List<List<String>>>()
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val rows = zip.bufferedReader().readLines()
                            .filter { it.isNotBlank() }
                            .map { parseCsvLine(it) }
                        files[entry.name] = rows
                    }
                    entry = zip.nextEntry
                }
            }
            fun table(name: String): List<Map<String, String>> {
                val rows = files[name] ?: emptyList()
                if (rows.isEmpty()) return emptyList()
                val header = rows.first()
                return rows.drop(1).map { row -> header.zip(row).toMap() }
            }

            val routes = table("routes.txt").map {
                RouteDto(routeId = it["route_id"]!!, label = it["route_short_name"].orEmpty() + " " + it["route_long_name"].orEmpty())
            }
            val stops = table("stops.txt").map {
                StopDto(id = it["stop_id"]!!, name = it["stop_name"].orEmpty(), lat = it["stop_lat"]!!.toDouble(), lon = it["stop_lon"]!!.toDouble())
            }
            val shapeIdsByRoute = table("trips.txt").groupBy({ it["route_id"]!! }, { it["shape_id"]!! })
            val pointsByShape = table("shapes.txt")
                .groupBy { it["shape_id"]!! }
                .mapValues { (_, rows) ->
                    rows.sortedBy { it["shape_pt_sequence"]!!.toInt() }
                        .map { ShapePoint(lat = it["shape_pt_lat"]!!.toDouble(), lon = it["shape_pt_lon"]!!.toDouble()) }
                }
            val shapesByRoute = shapeIdsByRoute.mapValues { (_, ids) -> ids.distinct().mapNotNull { pointsByShape[it] } }
            return GtfsStatic(routes, stops, shapesByRoute)
        }

        private fun parseCsvLine(line: String): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            var inQuotes = false
            for (c in line) {
                when {
                    c == '"' -> inQuotes = !inQuotes
                    c == ',' && !inQuotes -> { out += sb.toString(); sb.clear() }
                    else -> sb.append(c)
                }
            }
            out += sb.toString()
            return out
        }
    }
}

data class StopDto(val id: String, val name: String, val lat: Double, val lon: Double)
data class ShapePoint(val lat: Double, val lon: Double)
