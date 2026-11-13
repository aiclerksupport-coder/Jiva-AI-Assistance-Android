package com.jiva.assistance

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirstRunNoticeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun warningIsShownAndNeedsAcknowledgementBeforeContinue() {
        composeRule.setContent { FirstRunNotice() }
        composeRule.onNodeWithText("Welcome to Jiva · कृपया पहले पढ़ें").assertExists()
        composeRule.onNodeWithText("Continue to Jiva").assertExists()
        composeRule.onNodeWithText("I have read this warning / मैंने चेतावनी पढ़ ली है").assertExists()
    }
}
