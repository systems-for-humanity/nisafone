package app.s4h.nisafone.feature.recording

import android.app.AlarmManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import co.touchlab.kermit.Logger

class AndroidAutoStartScheduleTimer(context: Context) : AutoStartScheduleTimer {
    private val logger = Logger.withTag("AndroidAutoStartScheduleTimer")
    private val alarmManager = context.applicationContext.getSystemService(AlarmManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var currentAlarm: AlarmManager.OnAlarmListener? = null

    override fun schedule(triggerAtEpochMillis: Long, onTrigger: () -> Unit) {
        cancel()

        val listener = AlarmManager.OnAlarmListener {
            currentAlarm = null
            onTrigger()
        }
        currentAlarm = listener

        try {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                triggerAtEpochMillis,
                ALARM_TAG,
                listener,
                handler
            )
        } catch (e: SecurityException) {
            logger.w(e) { "Exact alarm unavailable; falling back to inexact alarm" }
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                triggerAtEpochMillis,
                ALARM_TAG,
                listener,
                handler
            )
        }
    }

    override fun cancel() {
        currentAlarm?.let(alarmManager::cancel)
        currentAlarm = null
    }

    private companion object {
        const val ALARM_TAG = "nisafone-auto-start-schedule"
    }
}
