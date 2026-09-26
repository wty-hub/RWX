package io.github.rwx

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.InputController
import com.corrodinggames.rts.gameFramework.MusicManager
import com.corrodinggames.rts.gameFramework.NullMusicFactory
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.SettingsEngine
import com.corrodinggames.rts.gameFramework.graphics.GraphicsEngine
import com.corrodinggames.rts.gameFramework.ui.Message
import com.corrodinggames.rts.gameFramework.ui.MessageManager
import io.github.rwx.diagnostics.GameStateTrace
import io.github.rwx.di.coreModule
import io.github.rwx.headless.HeadlessGameSession
import io.github.rwx.headless.HeadlessGraphicsEngine
import io.github.rwx.i18n.LocaleSettings
import io.github.rwx.settings.GameSettingsRepository
import io.github.rwx.ui.model.ReplaySelectViewModel
import io.github.rwx.ui.model.SettingsModel
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import java.io.File
import kotlin.system.exitProcess

/**
 * Runs a replay or a single-player map on the existing game loop without a window or OpenGL.
 *
 * ```
 * --replay=<name> | --map=<path>
 * --ticks=N          required with --map; optional cap for a replay
 * --checksum=<file>  per-tick checksum log
 * ```
 */
object HeadlessMain {
    private const val REPLAY_PREFIX = "--replay="
    private const val MAP_PREFIX = "--map="
    private const val TICKS_PREFIX = "--ticks="
    private const val CHECKSUM_PREFIX = "--checksum="
    private const val HEADLESS_FLAG = "--headless"
    private const val STEP_CROSS_EPSILON = 0.001f
    private const val FRAME_MILLIS = 16
    private const val EXIT_OK = 0
    private const val EXIT_FAILURE = 1
    private const val EXIT_REPLAY_READ = 2
    private const val UNEXPECTED_REPLAY_END = "Replay ended (unexpected)"

    private val replayStoppedField = ReplayEngine::class.java.getDeclaredField("isStopped").apply {
        isAccessible = true
    }
    private val messagesField = MessageManager::class.java.getDeclaredField("messages").apply {
        isAccessible = true
    }
    private val messageTextField = Message::class.java.getDeclaredField("text").apply {
        isAccessible = true
    }

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val code = try {
            run(parseArgs(args))
        } catch (error: Throwable) {
            System.err.println("Headless run failed: ${error.message}")
            error.printStackTrace(System.err)
            EXIT_FAILURE
        }
        GameStateTrace.close()
        exitProcess(code)
    }

    internal fun parseArgs(args: Array<String>): HeadlessOptions {
        var replay: String? = null
        var map: String? = null
        var ticks: Int? = null
        var checksum: String? = null
        args.forEach { arg ->
            when {
                arg == HEADLESS_FLAG -> Unit
                arg.startsWith(REPLAY_PREFIX) -> {
                    replay = arg.removePrefix(REPLAY_PREFIX).trim().also {
                        require(it.isNotEmpty()) { "Replay name must not be empty" }
                    }
                }
                arg.startsWith(MAP_PREFIX) -> {
                    map = arg.removePrefix(MAP_PREFIX).trim().also {
                        require(it.isNotEmpty()) { "Map path must not be empty" }
                    }
                }
                arg.startsWith(TICKS_PREFIX) -> {
                    val raw = arg.removePrefix(TICKS_PREFIX).trim()
                    ticks = raw.toIntOrNull()?.takeIf { it > 0 }
                        ?: throw IllegalArgumentException("Tick count must be a positive integer: $raw")
                }
                arg.startsWith(CHECKSUM_PREFIX) -> {
                    checksum = arg.removePrefix(CHECKSUM_PREFIX).trim().also {
                        require(it.isNotEmpty()) { "Checksum path must not be empty" }
                    }
                }
                else -> throw IllegalArgumentException("Unsupported headless argument: $arg")
            }
        }
        if ((replay == null) == (map == null)) {
            throw IllegalArgumentException("Pass exactly one of --replay= or --map=")
        }
        if (map != null && ticks == null) {
            throw IllegalArgumentException("--ticks=N is required with --map=")
        }
        return HeadlessOptions(
            replay = replay,
            map = map,
            ticks = ticks,
            checksum = checksum?.let(::File),
        )
    }

    private fun run(options: HeadlessOptions): Int {
        options.checksum?.let(GameStateTrace::start)
        configureDesktopLogging()
        configureHeadlessPlatform()
        GlobalContext.startKoin {
            modules(coreModule, headlessModule)
        }
        val koin = GlobalContext.get()
        (koin.get<CrashReporter>() as? FileCrashReporter)
            ?.installAsDefaultUncaughtExceptionHandler()
        val storage = koin.get<PlatformStorage>()
        storage.createDirectories()
        SettingsEngine.getInstance().save()
        val settings = SettingsModel()
        koin.get<GameSettingsRepository>().loadInto(settings)
        koin.get<GameSettingsRepository>().saveFrom(settings)
        LocaleSettings.initialize()
        val graphics = koin.get<GraphicsEngine>()
        GameEngine.graphicsEngine = graphics

        val session = HeadlessGameSession()
        val engine = session.boot(graphics)
        val replayName = options.replay
        if (replayName != null) {
            session.openReplay(resolveReplayName(replayName))
        } else {
            session.openMap(options.map!!)
        }

        val code = advance(engine, options.ticks, replay = replayName != null)
        val label = replayName?.let { "replay '$it'" } ?: "map '${options.map}'"
        println("Headless $label finished at tick ${engine.currentTick}")
        options.checksum?.let { println("Checksum: ${it.absolutePath}") }
        return code
    }

    private fun resolveReplayName(query: String): String {
        val replays = ReplaySelectViewModel().items()
        val replay = replays.firstOrNull { it.fileName == query }
            ?: replays.firstOrNull { it.fileName.contains(query) }
            ?: throw IllegalArgumentException("Replay not found: $query")
        return replay.replayName
    }

    private fun advance(engine: GameEngine, ticks: Int?, replay: Boolean): Int {
        while (true) {
            if (ticks != null && engine.currentTick >= ticks) {
                return EXIT_OK
            }
            if (replay && replayFinished(engine)) {
                return if (unexpectedReplayEnd(engine)) EXIT_REPLAY_READ else EXIT_OK
            }
            val stepRate = engine.networkEngine.getCurrentStepRate().coerceAtLeast(0.1f)
            engine.gameLoop(stepRate + STEP_CROSS_EPSILON, FRAME_MILLIS)
        }
    }

    private fun replayFinished(engine: GameEngine): Boolean {
        val replay = engine.replayEngine ?: return false
        return replayStoppedField.getBoolean(replay) || !replay.i()
    }

    private fun unexpectedReplayEnd(engine: GameEngine): Boolean {
        val manager = engine.gameUI?.messageManager ?: return false
        val messages = messagesField.get(manager) as? Iterable<*> ?: return false
        return messages.any { message -> messageTextField.get(message) == UNEXPECTED_REPLAY_END }
    }

    private fun configureHeadlessPlatform() {
        GameEngine.isMenuBackgroundDisabled = true
        GameEngine.isNonAndroidVersion = true
        GameEngine.isDesktopInitialized = true
        GameEngine.isJavaDesktopVersion = true
        GameEngine.isPCOrIOSVersion = true
        GameEngine.isHeadlessMode = true
        GameEngine.externalGameLoopDriver = true
        InputController.b = DesktopInputHandler()
        MusicManager.musicFactory = NullMusicFactory()
    }
}

internal data class HeadlessOptions(
    val replay: String?,
    val map: String?,
    val ticks: Int?,
    val checksum: File?,
)

private val headlessModule = module {
    single<PlatformBridge> { DesktopPlatformBridge(audio = NoopPlatformAudio) }
    single<PlatformStorage> { get<PlatformBridge>().storage }
    single<PreferenceStorage> { get<PlatformBridge>().preferenceStorage }
    single<AppMetadata> { get<PlatformBridge>().appMetadata }
    single<AppLogger> { get<PlatformBridge>().logger }
    single<CrashReporter> { get<PlatformBridge>().crashReporter }
    single<GraphicsEngine> { HeadlessGraphicsEngine(get()) }
}
