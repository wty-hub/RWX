package io.github.rwx.ui.emoji

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmojiSupportTest {
    @Test
    fun `detects emoji and leaves plain text alone`() {
        assertFalse("hello 猫".containsEmoji())
        assertTrue("加🍎群".containsEmoji())
        assertTrue("1️⃣".containsEmoji())
        assertFalse("123".containsEmoji())
    }

    @Test
    fun `splits emoji runs from surrounding text`() {
        val runs = "加🍎群😀送".toTextRuns()
        assertEquals(
            listOf(
                TextRun.Plain("加"),
                TextRun.Emoji("🍎", intArrayOf(0x1F34E)),
                TextRun.Plain("群"),
                TextRun.Emoji("😀", intArrayOf(0x1F600)),
                TextRun.Plain("送"),
            ).map { it.display() },
            runs.map { it.display() },
        )
    }

    @Test
    fun `keeps adjacent emoji as separate clusters`() {
        assertEquals(
            listOf("E:🚀", "E:🚀"),
            "🚀🚀".toTextRuns().map { it.display() },
        )
        assertEquals(
            listOf("E:🇨🇳", "E:🇨🇳"),
            "🇨🇳🇨🇳".toTextRuns().map { it.display() },
        )
        assertEquals(
            listOf("E:🔥", "T: ", "E:👉", "T: ", "E:🎮"),
            "🔥 👉 🎮".toTextRuns().map { it.display() },
        )
    }

    @Test
    fun `keeps flag pairs and zwj families together`() {
        assertEquals(listOf("E:🇺🇳"), "🇺🇳".toTextRuns().map { it.display() })
        assertEquals(listOf("E:🇨🇳"), "🇨🇳".toTextRuns().map { it.display() })
        val family = "👨‍👩‍👧"
        assertEquals(listOf("E:$family"), family.toTextRuns().map { it.display() })
    }

    @Test
    fun `keycap sequences are a single emoji cluster`() {
        assertEquals(listOf("E:1️⃣"), "1️⃣".toTextRuns().map { it.display() })
        assertEquals(
            listOf("T:room ", "E:1️⃣"),
            "room 1️⃣".toTextRuns().map { it.display() },
        )
    }

    private fun TextRun.display(): String = when (this) {
        is TextRun.Plain -> "T:$value"
        is TextRun.Emoji -> "E:$value"
    }
}
