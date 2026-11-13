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

    @Test fun deduplicatesLabelNodesBelongingToSameClickableControl() {
        val candidates = listOf(
            VisibleTargetMatcher.Candidate(setOf("Send"), "send-button"),
            VisibleTargetMatcher.Candidate(setOf("Send"), "send-button"),
        )
        assertEquals(VisibleTargetMatcher.Resolution.Unique("send-button"), VisibleTargetMatcher.resolve("Send", candidates))
    }

    @Test fun rejectsMultipleDistinctVisibleTargets() {
        val candidates = listOf(
            VisibleTargetMatcher.Candidate(setOf("Like"), "like-1"),
            VisibleTargetMatcher.Candidate(setOf("Like"), "like-2"),
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

    @Test fun flagsCredentialAndPaymentScreenTextBestEffort() {
        assertTrue(VisibleTargetMatcher.looksLikePrivateOrCredentialScreen("Enter your password to sign in"))
        assertTrue(VisibleTargetMatcher.looksLikePrivateOrCredentialScreen("यूपीआई पिन दर्ज करें"))
        assertFalse(VisibleTargetMatcher.looksLikePrivateOrCredentialScreen("Instagram reel: Like, Comment, Share"))
    }

    @Test fun classifiesHighImpactButtonsForExtraReview() {
        assertTrue(VisibleTargetMatcher.isHighImpactLabel("Send now"))
        assertTrue(VisibleTargetMatcher.isHighImpactLabel("भुगतान करें"))
        assertFalse(VisibleTargetMatcher.isHighImpactLabel("Like"))
    }
}
