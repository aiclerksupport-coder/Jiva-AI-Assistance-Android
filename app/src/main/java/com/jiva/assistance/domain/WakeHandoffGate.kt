package com.jiva.assistance.domain

import java.util.concurrent.atomic.AtomicBoolean

/**
 * A single wake-word event is allowed per armed microphone session.
 * Calling [arm] is only safe after the wake-word engine has started.
 */
class WakeHandoffGate {
    private val claimed = AtomicBoolean(false)
    fun claim(): Boolean = claimed.compareAndSet(false, true)
    fun arm() { claimed.set(false) }
    fun disarm() { claimed.set(true) }
}
