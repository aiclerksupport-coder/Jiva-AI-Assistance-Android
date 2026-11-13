package com.jiva.assistance.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowPackagePolicyTest {
    @Test fun onlyMatchingForegroundApplicationCanReceiveConfirmedAction() {
        assertTrue(WindowPackagePolicy.isTrustedTarget("com.example.alpha", "com.example.alpha"))
        assertFalse(WindowPackagePolicy.isTrustedTarget("com.example.alpha", "com.example.beta"))
    }

    @Test fun nullBlankOrSpoofedPackageFailsClosed() {
        assertFalse(WindowPackagePolicy.isTrustedTarget(null, "com.example.alpha"))
        assertFalse(WindowPackagePolicy.isTrustedTarget("com.example.alpha", null))
        assertFalse(WindowPackagePolicy.isTrustedTarget(" ", "com.example.alpha"))
        assertFalse(WindowPackagePolicy.isTrustedTarget("com.example.alpha", " "))
        assertFalse(WindowPackagePolicy.isTrustedTarget("com.example.alpha", "com.example.alpha.other"))
    }
}
