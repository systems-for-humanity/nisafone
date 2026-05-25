package app.s4h.nisafone.feature.recording

interface AutoStartScheduleTimer {
    fun schedule(triggerAtEpochMillis: Long, onTrigger: () -> Unit)
    fun cancel()
}
