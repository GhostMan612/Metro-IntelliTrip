package com.intellitrip.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceCalendarTest {
    private val svc = ServiceKey(FeedId("f"), "SVC")
    private val monday = LocalDate(2026, 10, 5) // Monday
    private val saturday = LocalDate(2026, 10, 3) // Saturday

    private fun weekdayCalendar() = Calendar(
        serviceKey = svc,
        daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
        startDate = LocalDate(monday.year - 1, monday.month, monday.dayOfMonth),
        endDate = LocalDate(monday.year + 1, monday.month, monday.dayOfMonth),
    )

    @Test
    fun weekdayActiveMondayInactiveSaturday() {
        val cal = ServiceCalendar(listOf(weekdayCalendar()), emptyList())
        assertTrue(cal.isActive(svc, monday))
        assertFalse(cal.isActive(svc, saturday))
    }

    @Test
    fun removedExceptionDeactivatesWeekday() {
        val cal = ServiceCalendar(
            listOf(weekdayCalendar()),
            listOf(CalendarDate(svc, monday, ServiceExceptionType.REMOVED)),
        )
        assertFalse(cal.isActive(svc, monday))
        assertTrue(cal.isActive(svc, monday.plus(1, DateTimeUnit.DAY)))
    }

    @Test
    fun addedExceptionActivatesSaturday() {
        val cal = ServiceCalendar(
            listOf(weekdayCalendar()),
            listOf(CalendarDate(svc, saturday, ServiceExceptionType.ADDED)),
        )
        assertTrue(cal.isActive(svc, saturday))
    }

    @Test
    fun noCalendarDataAssumesActive() {
        assertTrue(ServiceCalendar().isActive(svc, saturday))
    }

    @Test
    fun legacyAllowListRestrictsDates() {
        val cal = ServiceCalendar(emptyList(), emptyList(), setOf(monday))
        assertTrue(cal.isActive(svc, monday))
        assertFalse(cal.isActive(svc, saturday))
    }

    @Test
    fun dateRangeIsRespected() {
        val cal = ServiceCalendar(
            listOf(
                Calendar(
                    serviceKey = svc,
                    daysOfWeek = setOf(DayOfWeek.MONDAY),
                    startDate = monday,
                    endDate = monday,
                ),
            ),
            emptyList(),
        )
        assertTrue(cal.isActive(svc, monday))
        assertFalse(cal.isActive(svc, monday.plus(1, DateTimeUnit.WEEK)))
    }
}
