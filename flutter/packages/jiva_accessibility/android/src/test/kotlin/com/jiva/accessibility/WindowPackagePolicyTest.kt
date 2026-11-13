package com.jiva.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowPackagePolicyTest {
    @Test fun matchesTheActiveWindowOnly() {
        assertTrue(WindowPackagePolicy.isTrustedTarget("com.example.alpha", "com.example.alpha", 17, 17))
        assertFalse(WindowPackagePolicy.isTrustedTarget("com.example.alpha", "com.example.beta", 17, 17))
        assertFalse(WindowPackagePolicy.isTrustedTarget(null, "com.example.alpha", 17, 17))
        assertFalse(WindowPackagePolicy.isTrustedTarget("com.example.alpha", null, 17, 17))
        assertFalse(WindowPackagePolicy.isTrustedTarget(" ", "com.example.alpha", 17, 17))
    }
    @Test fun eventFromAnotherWindowInSameAppMustNotClick() {
        assertFalse(WindowPackagePolicy.isTrustedTarget(
            "com.example.alpha", "com.example.alpha", 10, 11,
        ))
        assertFalse(WindowPackagePolicy.isTrustedTarget(
            "com.example.alpha", "com.example.alpha", -1, 11,
        ))
        assertFalse(WindowPackagePolicy.isTrustedTarget(
            "com.example.alpha", "com.example.alpha", 11, -1,
        ))
    }

}
