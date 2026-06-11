package app.s4h.nisafone.android

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests covering the app's basic functions on a real emulator:
 * launch, tab navigation, title prefix management, and that tapping
 * record without a downloaded model does not crash.
 */
@RunWith(AndroidJUnit4::class)
class BasicFunctionsTest {

    companion object {
        private const val LAUNCH_TIMEOUT_MS = 30_000L
        private const val UI_TIMEOUT_MS = 10_000L
    }

    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    )

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String, timeoutMs: Long = UI_TIMEOUT_MS) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForRecordingScreen() {
        composeRule.waitUntil(LAUNCH_TIMEOUT_MS) {
            composeRule.onAllNodesWithContentDescription("Start recording")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun appLaunches_showsRecordingScreen() {
        waitForRecordingScreen()

        composeRule.onNodeWithContentDescription("Start recording").assertIsDisplayed()
        composeRule.onNodeWithText("Tap the microphone to start recording").assertIsDisplayed()
        composeRule.onNodeWithText("Record").assertIsDisplayed()
        composeRule.onNodeWithText("History").assertIsDisplayed()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun navigation_betweenAllTabs() {
        waitForRecordingScreen()

        composeRule.onNodeWithText("History").performClick()
        waitForText("No recordings yet")
        composeRule.onNodeWithText("No recordings yet").assertIsDisplayed()

        composeRule.onNodeWithText("Settings").performClick()
        waitForText("Storage Used")
        composeRule.onNodeWithText("Storage Used").assertIsDisplayed()

        composeRule.onNodeWithText("Record").performClick()
        waitForRecordingScreen()
        composeRule.onNodeWithContentDescription("Start recording").assertIsDisplayed()
    }

    @Test
    fun titlePrefix_addNewPrefixViaDialog() {
        waitForRecordingScreen()

        // Unique name so reruns on the same device don't collide with
        // prefixes persisted in SharedPreferences
        val prefixName = "Meeting ${System.currentTimeMillis() % 100_000}"

        composeRule.onNodeWithText("Recording").performClick()
        waitForText("Add new…")
        composeRule.onNodeWithText("Add new…").performClick()

        waitForText("Add Title Prefix")
        composeRule.onNode(hasSetTextAction()).performTextInput(prefixName)
        composeRule.onNodeWithText("Add").performClick()

        // Dialog closes and the new prefix becomes the selected one
        waitForText(prefixName)
        composeRule.onNodeWithText(prefixName).assertIsDisplayed()
    }

    @Test
    fun tapRecordWithoutModel_doesNotCrash() {
        waitForRecordingScreen()

        // No speech model is downloaded on a fresh emulator, so recording
        // cannot start — the tap must be a safe no-op, not a crash
        composeRule.onNodeWithContentDescription("Start recording").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Start recording").assertIsDisplayed()
        composeRule.onNodeWithText("Record").assertIsDisplayed()
    }
}
