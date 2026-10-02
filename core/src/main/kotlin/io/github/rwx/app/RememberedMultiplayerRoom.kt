package io.github.rwx.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Successful room identity, rather than an endpoint shared by multiple rooms or a temporary P2P port. */
@Serializable
internal data class RememberedMultiplayerRoom(
    val connectDescriptor: String = "",
    val serverId: String? = null,
    val p2pRoomId: String? = null,
    val roomLabel: String = "server",
) {
    init {
        require(
            (p2pRoomId == null && connectDescriptor.isNotBlank()) ||
                (!p2pRoomId.isNullOrBlank() && connectDescriptor.isBlank() && serverId == null)
        )
    }

    fun encode(): String = codec.encodeToString(serializer(), this)

    companion object {
        private val codec = Json { ignoreUnknownKeys = true }
        fun decode(value: String?): RememberedMultiplayerRoom? = value?.takeIf { it.isNotBlank() }?.let {
            runCatching { codec.decodeFromString(serializer(), it) }.getOrNull()
        }
    }
}
