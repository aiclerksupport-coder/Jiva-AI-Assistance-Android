package com.jiva.assistance.domain

/** Only act when the event and the inspected active root belong to the same app. */
object WindowPackagePolicy {
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
