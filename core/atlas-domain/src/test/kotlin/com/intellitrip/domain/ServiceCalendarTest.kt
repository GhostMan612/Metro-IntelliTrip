package com.intellitrip.domain

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceCalendarTest {
    private val svc = ServiceKey(FeedId("f"), "SVC")
    private val monday = LocalDate.of(2026, 10, 5) // Monday
    private val saturday = LocalDate.of(2026, 10, 3) // Saturday

    private fun weekdayCalendar() = Calendar(
        serviceKey = svc,
        daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
        startDate = monday.minusYears(1),
        endDate = monday.plusYears(1),
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
        assertTrue(cal.isActive(svc, monday.plusDays(1)))
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
        assertFalse(cal.isActive(svc, monday.plusWeeks(1)))
    }
}
