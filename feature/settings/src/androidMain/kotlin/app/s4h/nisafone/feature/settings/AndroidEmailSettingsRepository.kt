package app.s4h.nisafone.feature.settings

import android.content.Context
import android.os.StrictMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidEmailSettingsRepository(context: Context) : EmailSettingsRepository {

    companion object {
        private const val PREFS_NAME = "email_settings"
        private const val PREF_AUTO_EMAIL_ENABLED = "auto_email_enabled"
        private const val PREF_EMAIL_ADDRESS = "email_address"
        private const val PREF_AUTO_START_SCHEDULE_ENABLED = "auto_start_schedule_enabled"
        private const val PREF_AUTO_START_SCHEDULES = "auto_start_schedules"

        // Legacy single-schedule keys kept for migration
        private const val PREF_AUTO_START_SCHEDULE_DAY_OF_WEEK = "auto_start_schedule_day_of_week"
        private const val PREF_AUTO_START_SCHEDULE_TIME = "auto_start_schedule_time"

        private const val DEFAULT_DAY_OF_WEEK = 1
        private const val DEFAULT_TIME = "09:00"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _autoEmailEnabled = MutableStateFlow(false)
    override val autoEmailEnabled: StateFlow<Boolean> = _autoEmailEnabled.asStateFlow()

    private val _emailAddress = MutableStateFlow("")
    override val emailAddress: StateFlow<String> = _emailAddress.asStateFlow()

    private val _autoStartScheduleEnabled = MutableStateFlow(false)
    override val autoStartScheduleEnabled: StateFlow<Boolean> = _autoStartScheduleEnabled.asStateFlow()

    private val _autoStartSchedules = MutableStateFlow<List<AutoStartSchedule>>(emptyList())
    override val autoStartSchedules: StateFlow<List<AutoStartSchedule>> = _autoStartSchedules.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        val oldPolicy = StrictMode.allowThreadDiskReads()
        try {
            _autoEmailEnabled.value = prefs.getBoolean(PREF_AUTO_EMAIL_ENABLED, false)
            _emailAddress.value = prefs.getString(PREF_EMAIL_ADDRESS, "") ?: ""
            _autoStartScheduleEnabled.value = prefs.getBoolean(PREF_AUTO_START_SCHEDULE_ENABLED, false)

            val rawSchedules = prefs.getString(PREF_AUTO_START_SCHEDULES, null)
            _autoStartSchedules.value = if (rawSchedules == null) {
                // Migrate legacy single schedule
                val legacyDay = prefs.getInt(PREF_AUTO_START_SCHEDULE_DAY_OF_WEEK, DEFAULT_DAY_OF_WEEK)
                val legacyTime = prefs.getString(PREF_AUTO_START_SCHEDULE_TIME, DEFAULT_TIME) ?: DEFAULT_TIME
                listOf(
                    AutoStartSchedule(
                        id = "legacy-default",
                        daysOfWeek = setOf(legacyDay),
                        time = legacyTime,
                        durationMinutes = AutoStartSchedule.DEFAULT_DURATION_MINUTES
                    ).normalized()
                )
            } else {
                AutoStartScheduleCodec.decode(rawSchedules)
            }
        } finally {
            StrictMode.setThreadPolicy(oldPolicy)
        }
    }

    override fun setAutoEmailEnabled(enabled: Boolean) {
        _autoEmailEnabled.value = enabled
        prefs.edit().putBoolean(PREF_AUTO_EMAIL_ENABLED, enabled).apply()
    }

    override fun setEmailAddress(address: String) {
        _emailAddress.value = address
        prefs.edit().putString(PREF_EMAIL_ADDRESS, address).apply()
    }

    override fun setAutoStartScheduleEnabled(enabled: Boolean) {
        _autoStartScheduleEnabled.value = enabled
        prefs.edit().putBoolean(PREF_AUTO_START_SCHEDULE_ENABLED, enabled).apply()
    }

    override fun setAutoStartSchedules(schedules: List<AutoStartSchedule>) {
        val normalized = schedules.map { it.normalized() }
        _autoStartSchedules.value = normalized
        prefs.edit().putString(PREF_AUTO_START_SCHEDULES, AutoStartScheduleCodec.encode(normalized)).apply()
    }
}
