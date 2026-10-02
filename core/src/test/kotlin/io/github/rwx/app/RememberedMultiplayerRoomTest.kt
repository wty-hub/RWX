package io.github.rwx.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class RememberedMultiplayerRoomTest {
    @Test
    fun `rooms sharing a relay retain their distinct connection descriptors and server ids`() {
        val first = RememberedMultiplayerRoom("get|room-one|111|false|5123", "room-one", roomLabel = "Room one")
        val second = RememberedMultiplayerRoom("get|room-two|222|false|5123", "room-two", roomLabel = "Room two")
        assertEquals(first, RememberedMultiplayerRoom.decode(first.encode()))
        assertEquals(second, RememberedMultiplayerRoom.decode(second.encode()))
        assertNotEquals(first.connectDescriptor, second.connectDescriptor)
    }

    @Test
    fun `P2P history persists room identity instead of its temporary tunnel address`() {
        val room = RememberedMultiplayerRoom(p2pRoomId = "peer-room-42", roomLabel = "朋友的房间")
        assertEquals(room, RememberedMultiplayerRoom.decode(room.encode()))
        assertEquals("", room.connectDescriptor)
    }

    @Test
    fun `direct connections persist and ambiguous legacy endpoints are not treated as room records`() {
        val room = RememberedMultiplayerRoom("[::1]:5123")
        assertEquals(room, RememberedMultiplayerRoom.decode(room.encode()))
        for (invalid in listOf(null, "", "127.0.0.1:5123", "{}", "{\"p2pRoomId\":\"\",\"connectDescriptor\":\"\"}")) {
            assertNull(RememberedMultiplayerRoom.decode(invalid))
        }
    }
}
