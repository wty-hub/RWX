package io.github.rwx.ui.model

import kotlin.test.Test
import kotlin.test.assertEquals

class MultiplayerRoomBrowserTest {
    private val rooms = listOf(
        room("Alice", "Crossing", state = "battleroom", current = 2, max = 10),
        room("Bob", "Ice Base", state = "ingame", current = 10, max = 10, password = true, mods = true),
        room("Cara", "Crossing", state = "chat", current = 1, max = 4, mods = true),
        room("Dave", "Beach", state = "locked", current = -1, max = 8),
    )

    @Test
    fun `search matches host or map and keeps the original order`() {
        assertEquals(listOf("Alice", "Cara"), visible(query = "cross").map { it.hostName })
        assertEquals(listOf("Bob"), visible(query = "BASE").map { it.hostName })
        assertEquals(listOf("Alice"), visible(query = "alice").map { it.hostName })
        assertEquals(rooms.map { it.hostName }, visible(query = "  ").map { it.hostName })
    }

    @Test
    fun `status filter separates waiting, in game, and everything else`() {
        assertEquals(listOf("Alice"), visible(status = MultiplayerStatusFilter.Waiting).map { it.hostName })
        assertEquals(listOf("Bob"), visible(status = MultiplayerStatusFilter.InGame).map { it.hostName })
        assertEquals(listOf("Cara", "Dave"), visible(status = MultiplayerStatusFilter.Other).map { it.hostName })
    }

    @Test
    fun `open slots ignore full rooms and unknown player counts`() {
        assertEquals(listOf("Alice", "Cara"), visible(slots = MultiplayerSlotFilter.Open).map { it.hostName })
    }

    @Test
    fun `password and mod filters can be combined with search`() {
        assertEquals(listOf("Bob"), visible(access = MultiplayerAccessFilter.Password).map { it.hostName })
        assertEquals(listOf("Alice", "Cara", "Dave"), visible(access = MultiplayerAccessFilter.Open).map { it.hostName })
        assertEquals(listOf("Bob", "Cara"), visible(mods = MultiplayerModFilter.Required).map { it.hostName })
        assertEquals(listOf("Alice", "Dave"), visible(mods = MultiplayerModFilter.None).map { it.hostName })
        assertEquals(
            listOf("Alice"),
            visible(
                query = "crossing",
                status = MultiplayerStatusFilter.Waiting,
                slots = MultiplayerSlotFilter.Open,
                access = MultiplayerAccessFilter.Open,
                mods = MultiplayerModFilter.None,
            ).map { it.hostName },
        )
    }

    private fun visible(
        query: String = "",
        status: MultiplayerStatusFilter = MultiplayerStatusFilter.All,
        slots: MultiplayerSlotFilter = MultiplayerSlotFilter.All,
        access: MultiplayerAccessFilter = MultiplayerAccessFilter.All,
        mods: MultiplayerModFilter = MultiplayerModFilter.All,
    ): List<MultiplayerRoomItem> = MultiplayerRoomBrowser.visibleRooms(rooms, query, status, slots, access, mods)

    private fun room(
        host: String,
        map: String,
        state: String,
        current: Int,
        max: Int,
        password: Boolean = false,
        mods: Boolean = false,
    ): MultiplayerRoomItem = MultiplayerRoomItem(
        roomId = host,
        hostName = host,
        mapName = map,
        playersLabel = "$current/$max",
        versionLabel = "v1",
        requiresPassword = password,
        hasMods = mods,
        transportLabel = "LAN",
        stateLabel = state,
        currentPlayers = current,
        maxPlayers = max,
    )
}
