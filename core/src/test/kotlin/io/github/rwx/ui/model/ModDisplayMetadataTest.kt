package io.github.rwx.ui.model

import io.github.rwx.mod.JvmModManifest
import io.github.rwx.mod.LegacyModManifest
import kotlin.test.Test
import kotlin.test.assertEquals

class ModDisplayMetadataTest {
    @Test fun `legacy mutable manifest changes cannot change published display entry`() {
        val live = LegacyModManifest().apply { id = "one"; name = "Old name"; description = "Old description" }
        val entry = ModEntry(live, true, "warning", "/mods/one")
        live.name = "Reloaded name"; live.description = "Reloaded description"; live.id = "two"
        assertEquals("one", entry.id)
        assertEquals("Old name", entry.name)
        assertEquals("Old description", entry.description)
    }
    @Test fun `JVM full display description does not mutate live manifest`() {
        val live = JvmModManifest("one", "Name", description = "Declared", author = "Author", version = "1", thumbnail = "image.png")
        val metadata = ModDisplayMetadata.from(live, "Runtime warnings and description")
        val entry = ModEntry(metadata, false)
        live.description = "Later reload"
        assertEquals("Runtime warnings and description", entry.description)
        assertEquals("Author", entry.author)
        assertEquals("1", entry.version)
        assertEquals("image.png", entry.thumbnail)
    }
}
