package com.jiva.assistance.domain

import kotlin.random.Random

/** AlarmManager PendingIntent request codes must not overwrite older reminders. */
object ReminderIdPolicy {
    fun next(usedIds: Set<Int>, draw: () -> Int = { Random.nextInt(1, Int.MAX_VALUE) }): Int {
        repeat(128) {
            val id = draw()
            if (id > 0 && id !in usedIds) return id
        }
        // Deterministic recovery after repeated collisions; fail if exhausted.
        for (id in 1..10000) if (id !in usedIds) return id
        throw IllegalStateException("Cannot allocate a unique reminder ID.")
    }
}
