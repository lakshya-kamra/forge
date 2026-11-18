package com.laksh.forge.domain.program

/** Which day comes next, and how the current week is described. */
object ProgramSchedule {

    /**
     * Name of the suggested next training day.
     * - Wave programs follow the current week's day list in order.
     * - Phased programs (Starting Strength) cycle within the phase of the last day done.
     * - Everything else rotates through the days in order.
     *
     * [dayNames] are the program's days in order; [recentDayNames] are the days of finished
     * sessions of this program, newest first.
     */
    fun nextDayName(
        config: ProgressionConfig?,
        weeks: List<LibraryWeek>?,
        dayNames: List<String>,
        recentDayNames: List<String>
    ): String? {
        if (dayNames.isEmpty()) return null
        val state = config?.state
        if (state != null && !weeks.isNullOrEmpty()) {
            val week = weeks.getOrNull(state.week - 1) ?: weeks.first()
            val day = week.days.getOrNull(state.sessionsInWeek) ?: week.days.lastOrNull()
            if (day != null && day.name in dayNames) return day.name
        }
        val last = recentDayNames.firstOrNull()?.let { dayNames.indexOf(it) }?.takeIf { it >= 0 }
            ?: return dayNames.first()
        val rotation = config?.engine?.rotation
        if (!rotation.isNullOrEmpty()) {
            val cycle = rotation.firstOrNull { last in it }
            if (cycle != null && cycle.size > 1) {
                val secondLast = recentDayNames.getOrNull(1)?.let { dayNames.indexOf(it) }
                val positions = cycle.indices.filter { cycle[it] == last }
                val pos = positions.firstOrNull { p ->
                    secondLast != null && cycle[(p - 1 + cycle.size) % cycle.size] == secondLast
                } ?: positions.first()
                return dayNames.getOrNull(cycle[(pos + 1) % cycle.size]) ?: dayNames.first()
            }
        }
        return dayNames[(last + 1) % dayNames.size]
    }

    /** "Week 2 of 4 · 3s week" style label, or null for programs without weeks. */
    fun weekLabel(config: ProgressionConfig?, weeks: List<LibraryWeek>?): String? {
        val state = config?.state ?: return null
        val total = weeks?.size?.takeIf { it > 0 } ?: config.engine.cycleWeeks
        val label = weeks?.getOrNull(state.week - 1)?.label
        val head = if (total != null) "Week ${state.week} of $total" else "Week ${state.week}"
        return listOfNotNull(head, label, if (state.cycle > 1) "cycle ${state.cycle}" else null).joinToString(" · ")
    }

    fun isOptionalWeek(config: ProgressionConfig?): Boolean {
        val c = config ?: return false
        return c.state.week in c.engine.optionalWeeks
    }

    fun isDeloadWeek(config: ProgressionConfig?): Boolean {
        val c = config ?: return false
        return c.state.week in c.engine.deloadWeeks || c.state.week in c.engine.optionalWeeks
    }
}
