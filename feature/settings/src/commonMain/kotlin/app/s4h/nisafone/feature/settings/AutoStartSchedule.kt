package app.s4h.nisafone.feature.settings

data class AutoStartSchedule(
    val id: String,
    val daysOfWeek: Set<Int>,
    val time: String,
    val durationMinutes: Int
) {
    fun normalized(): AutoStartSchedule {
        val normalizedDays = daysOfWeek.filter { it in 1..7 }.toSet()
        return copy(
            daysOfWeek = if (normalizedDays.isEmpty()) setOf(1) else normalizedDays,
            durationMinutes = durationMinutes.coerceAtLeast(1)
        )
    }

    companion object {
        const val DEFAULT_DURATION_MINUTES = 30

        fun default(id: String): AutoStartSchedule {
            return AutoStartSchedule(
                id = id,
                daysOfWeek = setOf(1),
                time = "09:00",
                durationMinutes = DEFAULT_DURATION_MINUTES
            )
        }
    }
}

object AutoStartScheduleCodec {
    private const val ENTRY_SEPARATOR = "\n"
    private const val FIELD_SEPARATOR = "|"
    private const val DAY_SEPARATOR = ","

    fun encode(schedules: List<AutoStartSchedule>): String {
        return schedules.joinToString(ENTRY_SEPARATOR) { schedule ->
            val normalized = schedule.normalized()
            val dayString = normalized.daysOfWeek.sorted().joinToString(DAY_SEPARATOR)
            listOf(
                normalized.id,
                normalized.time,
                normalized.durationMinutes.toString(),
                dayString
            ).joinToString(FIELD_SEPARATOR)
        }
    }

    fun decode(raw: String): List<AutoStartSchedule> {
        if (raw.isBlank()) return emptyList()

        return raw
            .split(ENTRY_SEPARATOR)
            .mapNotNull { line ->
                val parts = line.split(FIELD_SEPARATOR)
                if (parts.size != 4) return@mapNotNull null

                val id = parts[0].trim()
                val time = parts[1].trim()
                val durationMinutes = parts[2].toIntOrNull() ?: return@mapNotNull null
                val days = parts[3]
                    .split(DAY_SEPARATOR)
                    .mapNotNull { it.toIntOrNull() }
                    .filter { it in 1..7 }
                    .toSet()

                if (id.isBlank()) return@mapNotNull null

                AutoStartSchedule(
                    id = id,
                    daysOfWeek = days,
                    time = time,
                    durationMinutes = durationMinutes
                ).normalized()
            }
    }
}
