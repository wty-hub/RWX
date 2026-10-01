package io.github.rwx.ui.model

import io.github.rwx.PlatformStorage
import io.github.rwx.StorageKind
import io.github.rwx.StorageLocation
import io.github.rwx.session.detachedCustomMapPaths
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class OwnerModMapCatalogTest {
    @Test fun `published map identifiers survive manager array reuse and cannot be mutated by consumers`() {
        val original = arrayOf("MOD|old/maps/[p2]old.tmx", "MOD|old/maps/[p4]second.tmx")
        val published = detachedCustomMapPaths(original)
        original[0] = "MOD|new/maps/[p8]new.tmx"
        assertEquals("MOD|old/maps/[p2]old.tmx", published[0])
        assertFailsWith<UnsupportedOperationException> { (published as MutableList)[0] = "changed" }
    }

    @Test fun `custom map browsing consumes detached owner entries without revisiting live metadata`() {
        // SavedGames suppresses preview probing in this fixture. Any attempt to rebuild the entry
        // from a live MOD path would require engine/file-loader state and violate the identity check.
        val old = MapEntry("/SD/rusted_warfare_maps/MOD|old/[p2]old.tmx", 2, listOf("original"), LevelSelectMode.SavedGames)
        val next = MapEntry("/SD/rusted_warfare_maps/MOD|new/[p4]new.tmx", 4, emptyList(), LevelSelectMode.SavedGames)
        var published = listOf(old)
        val model = LevelSelectViewModel(LevelSelectMode.CustomMaps, EmptyStorage,
            customMapPathsProvider = { emptyList() }, modMapEntriesProvider = { published })
        val displayed = model.items()
        published = listOf(next)
        assertSame(old, displayed.single())
        assertSame(next, model.items().single())
        assertEquals(listOf("original"), displayed.single().requiredRwxFeatures)
    }

    private object EmptyStorage : PlatformStorage {
        private val location = StorageLocation("test", File("/tmp"))
        override val rootDir = location
        override val localDir = location
        override val cacheDir = location
        override val modsDir = location
        override val unitsDir = location
        override val mapsDir = location
        override val savesDir = location
        override val replaysDir = location
        override fun location(kind: StorageKind) = location
        override fun listAssets(prefix: String) = emptyList<String>()
        override fun assetFileExists(path: String) = false
        override fun createDirectories() = Unit
    }
}
