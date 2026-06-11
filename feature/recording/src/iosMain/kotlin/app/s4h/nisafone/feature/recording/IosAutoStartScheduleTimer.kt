package app.s4h.nisafone.feature.recording

import platform.Foundation.NSDate
import platform.Foundation.NSTimer
import platform.Foundation.timeIntervalSince1970

class IosAutoStartScheduleTimer : AutoStartScheduleTimer {
    private var timer: NSTimer? = null

    override fun schedule(triggerAtEpochMillis: Long, onTrigger: () -> Unit) {
        cancel()

        val nowEpochMillis = NSDate().timeIntervalSince1970 * 1_000.0
        val delaySeconds = ((triggerAtEpochMillis - nowEpochMillis) / 1_000.0).coerceAtLeast(0.0)

        timer = NSTimer.scheduledTimerWithTimeInterval(
            ti = delaySeconds,
            repeats = false
        ) {
            timer?.invalidate()
            timer = null
            onTrigger()
        }
    }

    override fun cancel() {
        timer?.invalidate()
        timer = null
    }
}
