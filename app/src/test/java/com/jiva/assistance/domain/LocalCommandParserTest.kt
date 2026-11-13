package com.jiva.assistance.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCommandParserTest {
    private val parser = LocalCommandParser()

    @Test fun parsesEnglishCallAsConfirmationPlan() {
        val plan = parser.parse("Call Alex")
        assertNotNull(plan)
        assertEquals(AssistantAction.CALL, plan?.action)
        assertTrue(ActionPolicy.requiresConfirmation(plan!!.action))
        assertFalse(ActionPolicy.canDispatch(plan))
    }

    @Test fun parsesHindiSettingsAndTarget() {
        val plan = parser.parse("वाईफाई सेटिंग खोलो")
        assertNotNull(plan)
        assertEquals(AssistantAction.OPEN_SETTINGS, plan?.action)
        assertEquals("wifi", plan?.settingsTarget)
    }

    @Test fun parsesAppLaunchRequest() {
        val plan = parser.parse("open YouTube")
        assertEquals(AssistantAction.OPEN_APP, plan?.action)
        assertEquals("YouTube", plan?.appName)
        assertTrue(ActionPolicy.canDispatch(plan!!))
    }

    @Test fun unknownTextIsLeftForNaturalLanguageFallback() {
        assertNull(parser.parse("What is the weather tomorrow?"))
    }

    @Test fun chatDoesNotDispatchAnAndroidAction() {
        assertFalse(ActionPolicy.requiresConfirmation(AssistantAction.CHAT))
        assertFalse(ActionPolicy.canDispatch(IntentPlan(AssistantAction.CHAT, "Hello")))
    }
}
