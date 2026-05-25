package app.s4h.nisafone.feature.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSUserDefaults

class IosEmailSettingsRepository : EmailSettingsRepository {

    companion object {
        private const val KEY_AUTO_EMAIL_ENABLED = "auto_email_enabled"
        private const val KEY_EMAIL_ADDRESS = "email_address"
        private const val KEY_AUTO_START_SCHEDULE_ENABLED = "auto_start_schedule_enabled"
        private const val KEY_AUTO_START_SCHEDULES = "auto_start_schedules"

        // Legacy single-schedule keys kept for migration
        private const val KEY_AUTO_START_SCHEDULE_DAY_OF_WEEK = "auto_start_schedule_day_of_week"
        private const val KEY_AUTO_START_SCHEDULE_TIME = "auto_start_schedule_time"

        private const val DEFAULT_DAY_OF_WEEK = 1
        private const val DEFAULT_TIME = "09:00"
    }

    private val defaults = NSUserDefaults.standardUserDefaults

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
        _autoEmailEnabled.value = defaults.boolForKey(KEY_AUTO_EMAIL_ENABLED)
        _emailAddress.value = defaults.stringForKey(KEY_EMAIL_ADDRESS) ?: ""
        _autoStartScheduleEnabled.value = defaults.boolForKey(KEY_AUTO_START_SCHEDULE_ENABLED)
        val rawSchedules = defaults.stringForKey(KEY_AUTO_START_SCHEDULES)
        _autoStartSchedules.value = if (rawSchedules == null) {
            // Migrate legacy single schedule
            val legacyDay = defaults.integerForKey(KEY_AUTO_START_SCHEDULE_DAY_OF_WEEK)
                .toInt()
                .takeIf { it in 1..7 }
                ?: DEFAULT_DAY_OF_WEEK
            val legacyTime = defaults.stringForKey(KEY_AUTO_START_SCHEDULE_TIME) ?: DEFAULT_TIME
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
    }

    override fun setAutoEmailEnabled(enabled: Boolean) {
        _autoEmailEnabled.value = enabled
        defaults.setBool(enabled, KEY_AUTO_EMAIL_ENABLED)
    }

    override fun setEmailAddress(address: String) {
        _emailAddress.value = address
        defaults.setObject(address, KEY_EMAIL_ADDRESS)
    }

    override fun setAutoStartScheduleEnabled(enabled: Boolean) {
        _autoStartScheduleEnabled.value = enabled
        defaults.setBool(enabled, KEY_AUTO_START_SCHEDULE_ENABLED)
    }

    override fun setAutoStartSchedules(schedules: List<AutoStartSchedule>) {
        val normalized = schedules.map { it.normalized() }
        _autoStartSchedules.value = normalized
        defaults.setObject(AutoStartScheduleCodec.encode(normalized), KEY_AUTO_START_SCHEDULES)
    }
}
