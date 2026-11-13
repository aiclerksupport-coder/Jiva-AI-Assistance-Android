package com.jiva.assistance

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Basic device/emulator smoke coverage for the initial assistant screen. */
@RunWith(AndroidJUnit4::class)
class AssistantScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun dashboardShowsJivaAndTypedVoiceActions() {
        composeRule.onNodeWithText("Jiva").assertExists()
        composeRule.onNodeWithText("Talk or type to Jiva").assertExists()
        composeRule.onNodeWithText("🎙 Speak").assertExists()
        composeRule.onNodeWithText("Send").assertExists()
    }
}
