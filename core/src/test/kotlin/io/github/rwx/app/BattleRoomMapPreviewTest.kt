package io.github.rwx.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BattleRoomMapPreviewTest {
    @Test
    fun `switching to a map without a preview does not retain the previous image`() {
        var resolvedPath: String? = null
        val preview = battleRoomMapPreview(
            mapPath = "custom/决战中岛.tmx",
            isSavedGame = false,
            selectedMapPath = "maps/skirmish/island.tmx",
            selectedPreviewPath = "maps/skirmish/island_map.png",
        ) { resolvedPath = it; null }
        assertNull(preview)
        assertEquals("custom/决战中岛.tmx", resolvedPath)
    }

    @Test
    fun `switching maps resolves the new map image instead of the old selection`() {
        assertEquals("new_map.png", battleRoomMapPreview("new.tmx", false, "old.tmx", "old_map.png") {
            assertEquals("new.tmx", it)
            "new_map.png"
        })
        assertEquals("new_map.png", battleRoomMapPreview("new.tmx", false, "new.tmx", "new_map.png") {
            error("An exact cached selection should be reused")
        })
    }

    @Test
    fun `saved games and unknown maps show no preview even with a stale selection`() {
        for ((path, saved) in listOf("save.rwsave" to true, "" to false)) {
            assertNull(battleRoomMapPreview(path, saved, "old.tmx", "old_map.png") {
                error("There is no map preview to resolve")
            })
        }
    }
}
