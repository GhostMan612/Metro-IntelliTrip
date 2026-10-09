package com.intellitrip.offline

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Small in-memory GTFS feed used by offline tests; no network access required. */
internal object GtfsFixtureBytes {

    fun validFeed(): ByteArray {
        val files = linkedMapOf(
            "agency.txt" to "agency_id,agency_name,agency_url,agency_timezone\n1,Metro Transit,https://www.metrotransit.org,America/Chicago\n",
            "routes.txt" to "route_id,agency_id,route_short_name,route_long_name,route_type\n901,1,A,Blue Line,0\n",
            "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon\nS1,First,44.9700,-93.2600\nS2,Second,44.9850,-93.2650\n",
            "trips.txt" to "route_id,service_id,trip_id,shape_id\n901,SVC,T1,SH1\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nT1,08:00:00,08:00:00,S1,1\nT1,08:10:00,08:10:00,S2,2\n",
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nSVC,1,1,1,1,1,0,0,20261001,20261231\n",
        )
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
}