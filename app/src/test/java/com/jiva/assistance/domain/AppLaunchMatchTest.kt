package com.jiva.assistance.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLaunchMatchTest {
    private val labels = listOf("Phone", "PhonePe", "Google Maps", "Maps Go", "My Local Bank App")

    @Test fun exactWinsOverPrefix() {
        assertEquals(0, AppLaunchMatch.select("phone", labels))
        assertEquals(1, AppLaunchMatch.select("PhonePe", labels))
    }

    @Test fun uniquePrefixCanResolveButAmbiguousCannot() {
        assertEquals(2, AppLaunchMatch.select("Google Map", labels))
        assertNull(AppLaunchMatch.select("Map", listOf("Maps", "Maps Go")))
        assertNull(AppLaunchMatch.select("Google", listOf("Google Maps", "Google Meet")))
    }

    @Test fun emptyOrShortOrUnknownDoesNotLaunchRandomApp() {
        assertNull(AppLaunchMatch.select("", labels))
        assertNull(AppLaunchMatch.select("   ", labels))
        assertNull(AppLaunchMatch.select("ph", labels))
        assertNull(AppLaunchMatch.select("Local Bank", labels))
        assertNull(AppLaunchMatch.select("SMS", labels))
    }

    @Test fun duplicateExactLabelsRemainAmbiguous() {
        assertNull(AppLaunchMatch.select("Maps", listOf("Maps", "Maps")))
    }
}
