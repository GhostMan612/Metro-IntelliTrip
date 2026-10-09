package com.intellitrip.gtfs

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object GtfsFixtures {

    fun zipOf(files: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    val minimalFeed: Map<String, String> = linkedMapOf(
        "agency.txt" to """
            agency_id,agency_name,agency_url,agency_timezone
            1,Metro Transit,https://www.metrotransit.org,America/Chicago
        """.trimIndent() + "\n",
        "routes.txt" to """
            route_id,agency_id,route_short_name,route_long_name,route_type
            901,1,A,Blue Line,0
        """.trimIndent() + "\n",
        "stops.txt" to """
            stop_id,stop_name,stop_lat,stop_lon
            34,Chicago Ave,44.97,-93.26
            39,17th St,44.96,-93.27
        """.trimIndent() + "\n",
        "trips.txt" to """
            route_id,service_id,trip_id,trip_headsign,direction_id,shape_id
            901,S1,1001,Northbound,0,SH1
        """.trimIndent() + "\n",
        "stop_times.txt" to """
            trip_id,arrival_time,departure_time,stop_id,stop_sequence
            1001,23:45:00,23:50:00,34,1
            1001,25:10:00,25:15:00,39,2
        """.trimIndent() + "\n",
        "calendar.txt" to """
            service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
            S1,1,1,1,1,1,0,0,20260101,20261231
        """.trimIndent() + "\n",
        "calendar_dates.txt" to """
            service_id,date,exception_type
            S1,20260704,2
        """.trimIndent() + "\n",
        "shapes.txt" to """
            shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence
            SH1,44.97,-93.26,2
            SH1,44.96,-93.27,1
        """.trimIndent() + "\n",
        "feed_info.txt" to """
            feed_publisher_name,feed_lang,feed_start_date,feed_end_date,feed_version
            Metro Transit,en,20260101,20261231,1
        """.trimIndent() + "\n",
    )
}