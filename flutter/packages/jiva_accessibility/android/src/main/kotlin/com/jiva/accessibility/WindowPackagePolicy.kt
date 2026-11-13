package com.jiva.accessibility

/** Prevent a stale event from acting on a different foreground app window. */
internal object WindowPackagePolicy {
    fun isTrustedTarget(
        eventPackage: String?,
        rootPackage: String?,
        eventWindowId: Int,
        rootWindowId: Int,
    ): Boolean {
        val event = eventPackage?.trim().orEmpty()
        val active = rootPackage?.trim().orEmpty()
        // Same package alone is insufficient: two windows in the same app
        // can race. Reject unknown IDs instead of clicking an arbitrary pane.
        return event.isNotEmpty() && event == active &&
            eventWindowId >= 0 && eventWindowId == rootWindowId
    }
}
