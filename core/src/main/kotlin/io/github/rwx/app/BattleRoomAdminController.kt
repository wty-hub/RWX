package io.github.rwx.app

import com.corrodinggames.rts.gameFramework.network.GameRoomSettings
import io.github.rwx.i18n.I18n
import io.github.rwx.logger
import io.github.rwx.session.BattleRoomTeamLayout
import io.github.rwx.session.GameSession
import io.github.rwx.session.resolveBattleRoomPlayerSnapshot
import io.github.rwx.ui.host.DialogSceneHost
import io.github.rwx.ui.model.Dialog
import io.github.rwx.ui.model.DialogButton

internal class BattleRoomAdminController(
    private val gameSession: GameSession,
    private val dialogSceneHost: DialogSceneHost,
    private val updateBattleRoomFromNetwork: () -> Unit,
    private val showUnavailableDialog: (String) -> Unit,
) {
    fun addAiToBattleRoom() {
        if (gameSession.currentBattleRoom() == null) {
            showUnavailableDialog("Add AI requires a hosted RW room")
            return
        }
        requestMutation("Add AI failed", "Unable to add AI") {
            check(gameSession.addBattleRoomAi(1)) { "Game session rejected add AI request" }
        }
    }

    fun showBattleRoomOptionsDialog() {
        val initialOptions = gameSession.currentBattleRoom()?.room?.options
        if (initialOptions == null) {
            showUnavailableDialog("Game options require a hosted RW room")
            return
        }
        dialogSceneHost.show(
            Dialog(
                title = "Game Options",
                message = "Configure this battle room before starting.",
                form = battleRoomOptionsForm(
                    initialOptions,
                    maxPlayers = gameSession.currentBattleRoom()?.maxPlayers ?: DEFAULT_MAX_PLAYERS,
                ),
                buttons = listOf(
                    DialogButton("Apply", onFormPress = ::applyBattleRoomOptionsForm),
                    DialogButton(I18n.common.cancel()),
                ),
                scrollableForm = true,
                compactOnAndroid = true,
            ),
        )
    }

    fun showPlayerConfigDialog(playerId: String) {
        val snapshot = gameSession.currentBattleRoom()
        val player = snapshot?.players
            ?.let { players -> resolveBattleRoomPlayerSnapshot(playerId, players) }
        if (player == null) {
            showUnavailableDialog("Player config requires a hosted RW room")
            return
        }
        val canKick = snapshot.isHost && !player.isLocal
        val buttons = buildList {
            add(
                DialogButton(
                    "Apply",
                    onFormPress = { values ->
                        requestMutation("Apply player config failed", "Unable to apply player config") {
                            val spawn = values["spawn"]?.toIntOrNull()
                            val team = values["team"]?.toIntOrNull()
                            // Host-only override fields are absent from a non-host form.
                            val startingUnits = values["startingUnits"]?.toIntOrNull()
                            val aiDifficulty = values["aiDifficulty"]?.toIntOrNull()
                            check(
                                gameSession.configureBattleRoomPlayer(
                                    player.id, spawn, team, startingUnits, aiDifficulty,
                                )
                            ) {
                                "Game session rejected player config request"
                            }
                        }
                    },
                ),
            )
            if (canKick) {
                add(DialogButton(if (player.isAI) "Remove AI" else "Kick") { kickBattleRoomPlayer(player.id) })
            }
            add(DialogButton(I18n.common.cancel()))
        }
        dialogSceneHost.show(
            Dialog(
                title = "Player Config",
                message = "Set spawn point and ally team.",
                form = playerConfigForm(player, isHost = snapshot.isHost),
                buttons = buttons,
                compactOnAndroid = true,
            ),
        )
    }

    private fun kickBattleRoomPlayer(playerId: String) {
        requestMutation("Kick player failed", "Unable to kick player") {
            check(gameSession.kickBattleRoomPlayer(playerId)) { "Game session rejected kick request" }
        }
    }

    fun sendBattleRoomChatMessage(message: String) {
        val text = message.trim()
        if (text.isBlank()) return
        if (gameSession.currentBattleRoom() == null) {
            showUnavailableDialog("Chat requires a hosted RW room")
            return
        }
        requestMutation("Send battle room chat failed", "Unable to send chat", refreshRoom = false) {
            check(gameSession.sendBattleRoomMessage(text)) { "Game session rejected chat request" }
        }
    }

    private fun applyTeamLayout(layout: BattleRoomTeamLayout) {
        if (gameSession.currentBattleRoom() == null) {
            showUnavailableDialog("Set Teams requires a hosted RW room")
            return
        }
        requestMutation("Set team layout failed", "Unable to set teams") {
            check(gameSession.applyBattleRoomTeamLayout(layout)) {
                "Game session rejected team layout request"
            }
        }
    }

    private fun applyBattleRoomOptionsForm(values: Map<String, String>) {
        val base = gameSession.currentBattleRoom()?.room?.options ?: GameRoomSettings()
        val options = values.toGameRoomSettings(base)
        val layout = values["teamLayout"]?.toBattleRoomTeamLayoutOrNull()
        if (gameSession.currentBattleRoom() == null) {
            showUnavailableDialog("Game options require a hosted RW room")
            return
        }
        requestMutation("Apply battle room options failed", "Unable to apply options") {
            check(gameSession.applyBattleRoomOptions(options)) {
                "Game session rejected battle room options"
            }
            values["maxPlayers"]?.toIntOrNull()?.let { maxPlayers ->
                gameSession.setBattleRoomMaxPlayers(maxPlayers)
            }
            layout?.let {
                check(gameSession.applyBattleRoomTeamLayout(it)) {
                    "Game session rejected team layout request"
                }
            }
        }
    }

    private fun requestMutation(
        logMessage: String,
        failurePrefix: String,
        refreshRoom: Boolean = true,
        action: () -> Unit,
    ) {
        gameSession.requestSessionTask(action) { result ->
            result.onSuccess { if (refreshRoom) updateBattleRoomFromNetwork() }.onFailure { error ->
                logger.warn(error) { logMessage }
                showUnavailableDialog("$failurePrefix: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }
}
