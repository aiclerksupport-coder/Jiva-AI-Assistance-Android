package com.jiva.assistance.domain

/** Only act when the event and the inspected active root belong to the same app. */
object WindowPackagePolicy {
    fun isTrustedTarget(eventPackage: String?, rootPackage: String?): Boolean {
        val event = eventPackage?.trim().orEmpty()
        val active = rootPackage?.trim().orEmpty()
        return event.isNotEmpty() && event == active
    }
}
