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

    @Test fun parsesAppLaunchInIndianLanguagesAndHinglish() {
        val commands = mapOf(
            "WhatsApp kholo" to "WhatsApp",
            "Instagram app chalao" to "Instagram",
            "यूट्यूब खोलो" to "यूट्यूब",
            "टेलीग्राम चालू करो" to "टेलीग्राम",
            "વોટ્સએપ ખોલો" to "વોટ્સએપ",
            "வாட்ஸ்அப் திற" to "வாட்ஸ்அப்",
            "యూట్యూబ్ తెరువు" to "యూట్యూబ్",
            "ফেসবুক খোলো" to "ফেসবুক",
            "ਇੰਸਟਾਗ੍ਰਾਮ ਖੋਲ੍ਹੋ" to "ਇੰਸਟਾਗ੍ਰਾਮ",
            "یوٹیوب کھولو" to "یوٹیوب",
        )
        commands.forEach { (command, expectedApp) ->
            val plan = parser.parse(command)
            assertEquals(command, AssistantAction.OPEN_APP, plan?.action)
            assertEquals(command, expectedApp, plan?.appName)
            assertTrue(ActionPolicy.requiresConfirmation(plan!!.action))
        }
    }

    @Test fun parsesPhoneNavigationOnlyForSupportedExplicitActions() {
        val commands = mapOf(
            "Go home" to "home",
            "Go back" to "back",
            "Recent apps" to "recents",
            "Show notifications" to "notifications",
            "Open quick settings" to "quick_settings",
        )
        commands.forEach { (command, expectedAction) ->
            val parsed = parser.parse(command)
            assertEquals(AssistantAction.PHONE_NAVIGATION, parsed?.action)
            assertEquals(expectedAction, parsed?.phoneAction)
        }
        val plan = parser.parse("open quick settings")
        assertEquals(AssistantAction.PHONE_NAVIGATION, plan?.action)
        assertEquals("quick_settings", plan?.phoneAction)
        assertTrue(ActionPolicy.requiresConfirmation(plan!!.action))
        assertTrue(ActionPolicy.canDispatch(plan))
        assertFalse(
            ActionPolicy.canDispatch(
                IntentPlan(AssistantAction.PHONE_NAVIGATION, "Navigate", phoneAction = "install_app"),
            ),
        )
        assertNull(parser.parse("I need help with homework"))
    }

    @Test fun unknownTextIsLeftForNaturalLanguageFallback() {
        assertNull(parser.parse("What is the weather tomorrow?"))
    }

    @Test fun chatDoesNotDispatchAnAndroidAction() {
        assertFalse(ActionPolicy.requiresConfirmation(AssistantAction.CHAT))
        assertFalse(ActionPolicy.canDispatch(IntentPlan(AssistantAction.CHAT, "Hello")))
    }
}
