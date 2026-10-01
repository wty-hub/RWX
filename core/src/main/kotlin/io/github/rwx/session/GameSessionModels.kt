package io.github.rwx.session

import com.corrodinggames.rts.gameFramework.network.GameModeType
import com.corrodinggames.rts.gameFramework.network.GameRoomSettings
import io.github.rwx.ui.model.BattleRoomPlayer
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.render.canvas.KoolCanvasRect
import java.util.Collections

/** Strings are immutable; never publish the original manager's mutable collection/array. */
fun detachedCustomMapPaths(paths: Array<String>?): List<String> =
    if (paths.isNullOrEmpty()) emptyList() else Collections.unmodifiableList(paths.toList())

/** Camera belonging to the displayed frame, rather than a live simulation object. */
data class GameCameraSnapshot(
    val revision: Long,
    val viewportRevision: Long,
    val viewport: KoolCanvasViewport,
    val x: Float,
    val y: Float,
    val zoom: Float,
    val generation: Long = 0L,
    val sidebarWidth: Float = 0f,
    val hudLayout: GameHudLayoutSnapshot? = null,
)

/** Detached geometry only; packet and input layouts can come from different original draw phases. */
data class GameHudLayoutSnapshot(
    val minimap: KoolCanvasRect?,
    val unselectButton: KoolCanvasRect?,
    val actionTop: Float,
    val actionRowPitch: Float,
    val unitGroupButtons: List<KoolCanvasRect>,
    val screenScale: Float,
    val standardSidebar: Boolean,
)

/** The displayed camera and surface dimensions captured together when input arrives. */
data class GamePointerFrameContext(
    val camera: GameCameraSnapshot?,
    /** Dimensions in the same coordinate units as the incoming pointer, after platform scaling. */
    val surfaceViewport: KoolCanvasViewport? = null,
) {
    fun positionInSeenViewport(x: Float, y: Float): Pair<Float, Float> {
        val seenViewport = camera?.viewport ?: return x to y
        val surface = surfaceViewport ?: return x to y
        if (surface.width <= 0 || surface.height <= 0 || seenViewport.width <= 0 || seenViewport.height <= 0) {
            return x to y
        }
        // The canvas stretches its completed packet independently along each framebuffer axis.
        return x * seenViewport.width / surface.width to y * seenViewport.height / surface.height
    }
}

data class GameLoadingStatus(
    val text: String = "Loading...",
    val progress: Float? = null,
    val recentSteps: List<String> = emptyList(),
) {
    init {
        require(progress == null || progress in 0.0f..1.0f) { "progress must be null or in 0..1" }
    }
}

data class GameSessionRendererProfile(
    val rendersIntoKoolCanvas: Boolean = true,
    val acceptsKoolInput: Boolean = true,
    val canStartNewSessionInPlace: Boolean = false,
    val usesNativeSurfaceForResumeBackground: Boolean = false,
)

data class MapSnapshot(
    val mapPath: String,
    val saveBytes: ByteArray,
    val battleRoomConfig: BattleRoomLaunchConfig? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MapSnapshot) return false
        return mapPath == other.mapPath &&
                saveBytes.contentEquals(other.saveBytes) &&
                battleRoomConfig == other.battleRoomConfig
    }

    override fun hashCode(): Int =
        31 * (31 * mapPath.hashCode() + saveBytes.contentHashCode()) +
                battleRoomConfig.hashCode()
}

data class BattleRoomCoreConfig(
    val mapPath: String,
    val options: GameRoomSettings,
) {
    val isSavedGame: Boolean get() = options.gameModeType == GameModeType.savedGame
}

data class BattleRoomLaunchConfig(
    val sandbox: Boolean = false,
    val aiPlayerCount: Int = 0,
    val teamLayout: BattleRoomTeamLayout? = null,
    val room: BattleRoomCoreConfig,
)


data class BattleRoomSnapshot(
    val room: BattleRoomCoreConfig,
    val mapDisplayName: String,
    val mapTypeLabel: String,
    val players: List<BattleRoomPlayer>,
    val isHost: Boolean,
    val isNetworkMultiplayer: Boolean = false,

    val rwxP2PSession: Boolean = false,
    /** Short summary of the host's active mods when the room requires them, as in the original game. */
    val requiredModsSummary: String? = null,
    /** Original battleroom status text, including network and server visibility details. */
    val networkStatusText: String? = null,
    /** Max player slots in the room (engine-global team count; not a GameRoomSettings field). */
    val maxPlayers: Int = 10,
    val startingUnitsLabel: String? = null,
)

data class MultiplayerChatSnapshot(
    val text: String,
    val teamColorIndex: Int = -1,
)

enum class BattleRoomTeamLayout {
    TwoSides,
    ThreeSides,
    Ffa,
    Spectators,
    Random,
    AllVsAi,
    AllVs2,
}
