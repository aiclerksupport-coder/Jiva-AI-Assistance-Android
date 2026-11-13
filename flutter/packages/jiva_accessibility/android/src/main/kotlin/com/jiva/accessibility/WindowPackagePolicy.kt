package com.jiva.accessibility

/** Prevent a stale event from acting on a different foreground app window. */
internal object WindowPackagePolicy {
    fun isTrustedTarget(eventPackage: String?, rootPackage: String?): Boolean {
        val event = eventPackage?.trim().orEmpty()
        val active = rootPackage?.trim().orEmpty()
        return event.isNotEmpty() && event == active
    }
}
