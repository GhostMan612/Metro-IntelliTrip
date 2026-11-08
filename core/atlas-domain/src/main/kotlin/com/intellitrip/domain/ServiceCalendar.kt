package com.intellitrip.domain

import java.time.LocalDate

/**
 * Single source of truth for GTFS calendar service activity.
 *
 * Both the planner ([com.intellitrip.routing.RoutingNetwork]) and the departure
 * board delegate here so they cannot disagree: a trip the board shows must be a
 * trip the planner will plan, and vice versa.
 *
 * Rules (GTFS schedule reference):
 * - `calendar.txt` activates on its weekdays within [Calendar.startDate]..[Calendar.endDate].
 * - `calendar_dates.txt` ADDED activates even outside a calendar row; REMOVED
 *   deactivates even inside one.
 * - With no calendar data at all, assume active (minimal fixtures keep working).
 * - [legacyActiveDates] (when non-empty and no calendar data) acts as an explicit
 *   allow-list: only those dates are active.
 */
class ServiceCalendar(
    private val calendars: List<Calendar> = emptyList(),
    private val calendarDates: List<CalendarDate> = emptyList(),
    private val legacyActiveDates: Set<LocalDate> = emptySet(),
) {
    fun isActive(serviceKey: ServiceKey, date: LocalDate): Boolean {
        if (calendars.isEmpty() && calendarDates.isEmpty()) {
            if (legacyActiveDates.isNotEmpty()) return date in legacyActiveDates
            return true
        }
        val exception = calendarDates.firstOrNull { it.serviceKey == serviceKey && it.date == date }
        if (exception != null) return exception.exceptionType == ServiceExceptionType.ADDED
        val day = date.dayOfWeek
        return calendars.any {
            it.serviceKey == serviceKey &&
                day in it.daysOfWeek &&
                !date.isBefore(it.startDate) &&
                !date.isAfter(it.endDate)
        }
    }

    fun servicesOn(date: LocalDate, knownServices: Set<ServiceKey>): Set<ServiceKey> {
        if (calendars.isEmpty() && calendarDates.isEmpty()) {
            if (legacyActiveDates.isEmpty()) return emptySet()
            return if (date in legacyActiveDates) knownServices else emptySet()
        }
        val keys = (calendars.map { it.serviceKey } + calendarDates.map { it.serviceKey } + knownServices).toSet()
        return keys.filterTo(mutableSetOf()) { isActive(it, date) }
    }
}
