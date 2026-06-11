package app.s4h.nisafone.feature.recording

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import co.touchlab.kermit.Logger

class AndroidAutoStartScheduleTimer(context: Context) : AutoStartScheduleTimer {
    private val logger = Logger.withTag("AndroidAutoStartScheduleTimer")
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var currentAlarm: AlarmManager.OnAlarmListener? = null

    override fun schedule(triggerAtEpochMillis: Long, onTrigger: () -> Unit) {
        cancel()

        val listener = AlarmManager.OnAlarmListener {
            currentAlarm = null
            notifyIfInBackground()
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

    // Android blocks microphone input for backgrounded apps, and a mic-type
    // foreground service may not start from the background either — so a
    // scheduled recording captures silence until the user opens the app.
    // Prompt them with a notification when the alarm fires in the background.
    private fun notifyIfInBackground() {
        val processState = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(processState)
        if (processState.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
            return
        }

        try {
            val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Scheduled recording",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )

            val tapIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            val pendingIntent = tapIntent?.let {
                PendingIntent.getActivity(
                    appContext,
                    0,
                    it,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }

            val notification = Notification.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(Icon.createWithResource(appContext, appContext.applicationInfo.icon))
                .setContentTitle("Scheduled recording started")
                .setContentText("Open the app so the microphone can record")
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .apply { pendingIntent?.let(::setContentIntent) }
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            logger.w(e) { "Failed to post auto-start notification" }
        }
    }

    private companion object {
        const val ALARM_TAG = "nisafone-auto-start-schedule"
        const val CHANNEL_ID = "auto_start_channel"
        const val NOTIFICATION_ID = 2
    }
}
