package com.jiva.assistance.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleTargetMatcherTest {
    @Test fun requiresExactNormalizedLabelMatch() {
        val candidates = listOf(VisibleTargetMatcher.Candidate(setOf("Like"), "button-1"))
        assertEquals(VisibleTargetMatcher.Resolution.Unique("button-1"), VisibleTargetMatcher.resolve("  LIKE  ", candidates))
        assertEquals(VisibleTargetMatcher.Resolution.NotFound, VisibleTargetMatcher.resolve("Like this", candidates))
    }

    @Test fun supportsExactLocalLanguageLabelEquivalents() {
        val cases = listOf(
            "Like" to "पसंद",
            "Send" to "அனுப்பு",
            "Pay" to "ચૂકવો",
            "Delete" to "நீக்கு",
            "Comment" to "মন্তব্য",
            "Share" to "పంచుకోండి",
        )
        cases.forEachIndexed { index, (requested, visibleLabel) ->
            val candidates = listOf(VisibleTargetMatcher.Candidate(setOf(visibleLabel), "target-$index"))
            assertEquals(requested, VisibleTargetMatcher.Resolution.Unique("target-$index"), VisibleTargetMatcher.resolve(requested, candidates))
        }
    }

    @Test fun refusesUnknownTamilDeleteLabelRatherThanGuessing() {
        val candidates = listOf(VisibleTargetMatcher.Candidate(setOf("மீக்கு"), "wrong-label"))
        assertEquals(VisibleTargetMatcher.Resolution.NotFound, VisibleTargetMatcher.resolve("Delete", candidates))
    }

    @Test fun localizedAliasStillRequiresExactWholeLabel() {
        val candidates = listOf(VisibleTargetMatcher.Candidate(setOf("पसंद करें"), "like-button"))
        assertEquals(VisibleTargetMatcher.Resolution.NotFound, VisibleTargetMatcher.resolve("Like", candidates))
    }

    @Test fun deduplicatesLabelNodesBelongingToSameClickableControl() {
        val candidates = listOf(
            VisibleTargetMatcher.Candidate(setOf("Send"), "send-button"),
            VisibleTargetMatcher.Candidate(setOf("Send"), "send-button"),
        )
        assertEquals(VisibleTargetMatcher.Resolution.Unique("send-button"), VisibleTargetMatcher.resolve("Send", candidates))
    }

    @Test fun rejectsMultipleDistinctVisibleTargetsIncludingLocalizedAliases() {
        val candidates = listOf(
            VisibleTargetMatcher.Candidate(setOf("Like"), "like-1"),
            VisibleTargetMatcher.Candidate(setOf("पसंद"), "like-2"),
        )
        assertEquals(VisibleTargetMatcher.Resolution.Ambiguous, VisibleTargetMatcher.resolve("Like", candidates))
    }

    @Test fun ignoresHiddenDisabledAndNonClickableCandidates() {
        val candidates = listOf(
            VisibleTargetMatcher.Candidate(setOf("Continue"), "hidden", visible = false),
            VisibleTargetMatcher.Candidate(setOf("Continue"), "disabled", enabled = false),
            VisibleTargetMatcher.Candidate(setOf("Continue"), "not-clickable", clickable = false),
        )
        assertEquals(VisibleTargetMatcher.Resolution.NotFound, VisibleTargetMatcher.resolve("Continue", candidates))
    }

    @Test fun flagsPrivateScreensAcrossIndicLanguagesAndCredentialVariants() {
        listOf(
            "Enter your password to sign in",
            "Enter OTP to continue",
            "यूपीआई पिन दर्ज करें",
            "வங்கி கணக்கு",
            "ਕਾਰਡ ਨੰਬਰ",
            "بینک اکاؤنٹ",
        ).forEach { text -> assertTrue("Should block: $text", VisibleTargetMatcher.looksLikePrivateOrCredentialScreen(text)) }
        assertFalse(VisibleTargetMatcher.looksLikePrivateOrCredentialScreen("Instagram reel: Like, Comment, Share"))
        assertFalse(VisibleTargetMatcher.looksLikePrivateOrCredentialScreen("The sender liked this post"))
    }

    @Test fun classifiesHighImpactActionsPreciselyAcrossLanguages() {
        assertEquals(VisibleTargetMatcher.ReviewRisk.COMMUNICATION, VisibleTargetMatcher.reviewRisk("Send"))
        assertEquals(VisibleTargetMatcher.ReviewRisk.FINANCIAL, VisibleTargetMatcher.reviewRisk("ચૂકવો"))
        assertEquals(VisibleTargetMatcher.ReviewRisk.DESTRUCTIVE, VisibleTargetMatcher.reviewRisk("Delete"))
        assertEquals(VisibleTargetMatcher.ReviewRisk.SOCIAL_POST, VisibleTargetMatcher.reviewRisk("Comment"))
        assertEquals(VisibleTargetMatcher.ReviewRisk.SOCIAL_POST, VisibleTargetMatcher.reviewRisk("Share"))
        assertEquals(VisibleTargetMatcher.ReviewRisk.SOCIAL_POST, VisibleTargetMatcher.reviewRisk("Like"))
        assertTrue(VisibleTargetMatcher.isHighImpactLabel("भुगतान करें"))
        assertFalse(VisibleTargetMatcher.isHighImpactLabel("Continue"))
    }
}
