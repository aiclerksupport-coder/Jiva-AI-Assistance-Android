package com.jiva.assistance.domain

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Lossless v2 persistence with backwards-compatible parsing of historical
 * "id|time|text" rows. Text can contain pipes, emoji and newlines.
 */
object ReminderRowCodec {
    fun encode(item: ReminderItem): String {
        val body = Base64.getEncoder().encodeToString(
            item.text.toByteArray(StandardCharsets.UTF_8),
        )
        return "v2|${item.id}|${item.triggerAtMillis}|$body"
    }

    fun decode(row: String): ReminderItem? {
        val p = row.split('|', limit = 4)
        return try {
            if (p.size == 4 && p[0] == "v2") {
                val id = p[1].toIntOrNull() ?: return null
                val time = p[2].toLongOrNull() ?: return null
                val body = String(Base64.getDecoder().decode(p[3]), StandardCharsets.UTF_8)
                if (id <= 0 || time <= 0 || body.isBlank()) null
                else ReminderItem(id, body, time)
            } else {
                // Historical data used arbitrary signed hash IDs; preserve it.
                val legacy = row.split('|', limit = 3)
                if (legacy.size != 3) return null
                val id = legacy[0].toIntOrNull() ?: return null
                val time = legacy[1].toLongOrNull() ?: return null
                if (time <= 0 || legacy[2].isBlank()) null
                else ReminderItem(id, legacy[2], time)
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
