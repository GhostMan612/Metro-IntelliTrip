package com.intellitrip

/**
 * Whether the planner anchors on leaving now/later or arriving by a deadline.
 *
 * Kept separate from [TravelMode]: a rider can want a transit trip that departs
 * at 08:15, or a walk estimate. They are different questions and the UI must
 * never silently turn one into the other.
 */
enum class PlanningTimeMode {
    DEPART_AT,
    ARRIVE_BY,
}
