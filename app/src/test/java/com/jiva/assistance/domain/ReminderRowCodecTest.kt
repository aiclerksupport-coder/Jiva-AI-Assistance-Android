package com.jiva.assistance.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderRowCodecTest {
    @Test fun preservesPipesEmojiNewlinesWithoutAlteringReminder() {
        val original = ReminderItem(77, "Check A|B\\nनमस्ते 🚪", 1893456000000L)
        assertEquals(original, ReminderRowCodec.decode(ReminderRowCodec.encode(original)))
    }

    @Test fun historicalReminderRowsStillLoad() {
        assertEquals(ReminderItem(-17, "legacy title", 1893456000000L),
            ReminderRowCodec.decode("-17|1893456000000|legacy title"))
    }

    @Test fun corruptOrInvalidRowsAreSafelySkipped() {
        for (row in listOf("", "v2|a|100|YQ==", "v2|2|3|%", "2|3|",
            "v2|0|10|YQ==", "v2|12|-2|YQ==")) {
            assertNull(row, ReminderRowCodec.decode(row))
        }
    }
}
