package io.github.rwx

import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.platform.swing.KoolGlCanvas
import de.fabmax.kool.platform.swing.SwingWindowSubsystem
import de.fabmax.kool.util.FrontendScope
import io.github.rwx.KoolDesktopMain.getKoin
import io.github.rwx.app.launchOnIO
import io.github.rwx.slick.SlickAwtGLCanvas
import io.github.rwx.slick.SlickCanvasHost
import io.github.rwx.slick.toSlickKey
import io.github.rwx.ui.component.PlatformTextInputBridge
import io.github.rwx.ui.emoji.EmojiRasterizerBridge
import kotlinx.coroutines.launch
import org.lwjgl.opengl.awt.GLData
import java.awt.*
import java.awt.event.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JFileChooser
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.system.exitProcess

class SwingKoolHost private constructor(
    val koolCanvas: Canvas,
    /** The AWT OpenGL canvas, or null when the Kool renderer draws the game into [koolCanvas]. */
    val gameCanvas: Canvas?,
    val windowSubsystem: PacedSwingWindowSubsystem,
    private val startupFullscreen: Boolean,
    private val singleWindowCapture: Boolean,
) : PlatformFilePickerHost {
    private val panel = JPanel(null)
    private val frame = JFrame(windowTitle())
    private val overlayPanel = JPanel(BorderLayout())
    private val overlayWindow = if (singleWindowCapture) null else JWindow(frame)
    private val textInputController: DesktopTextInputController
    private val emojiRasterizer = DesktopEmojiRasterizer()
    val windowSize: Vec2i
        get() {
            val width = panel.width.takeIf { it > 0 } ?: panel.preferredSize.width
            val height = panel.height.takeIf { it > 0 } ?: panel.preferredSize.height
            return Vec2i(width.coerceAtLeast(1), height.coerceAtLeast(1))
        }
    private var gameBufferStrategyCreated = false
    private var initialContentFitPending = true
    private var initialContentFitApplied = false
    private var applyingInitialContentFit = false
    private val closeRequested = AtomicBoolean(false)
    @Volatile
    private var hostFocusLostHandler: (() -> Unit)? = null
    private var pointerCursor: Cursor? = null
    private var cachedOverlayLocation: Point? = null
    private val keyboardFocusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    private val koolTypedControlCharacterFilter = KeyEventDispatcher { event ->
        event.source === koolCanvas && shouldSuppressKoolTypedCharacter(event)
    }

    init {
        val preferredWindowSize = initialWindowSize(startupFullscreen)
        panel.background = Color.BLACK
        panel.preferredSize = Dimension(preferredWindowSize.x, preferredWindowSize.y)
        panel.minimumSize = Dimension(800, 600)
        panel.isOpaque = true
        koolCanvas.name = KOOL_CARD
        koolCanvas.background = if (singleWindowCapture) Color.BLACK else TransparentCanvasColor
        koolCanvas.isFocusable = true
        koolCanvas.ignoreRepaint = true
        if (singleWindowCapture) {
            koolCanvas.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(event: KeyEvent) {
                    SlickCanvasHost.submitKoolCanvasKey(event.toSlickKey(), true)
                }

                override fun keyReleased(event: KeyEvent) {
                    SlickCanvasHost.submitKoolCanvasKey(event.toSlickKey(), false)
                }
            })
        }
        keyboardFocusManager.addKeyEventDispatcher(koolTypedControlCharacterFilter)
        textInputController = DesktopTextInputController(
            editorHost = if (singleWindowCapture) frame.layeredPane else checkNotNull(overlayWindow).layeredPane,
            activateEditorWindow = {
                // The hidden IME target must live in the same native window as the focused canvas.
                if (!singleWindowCapture) {
                    checkNotNull(overlayWindow).focusableWindowState = true
                    raiseOverlayIfActive()
                }
            },
            setEditorHasFocus = { hasFocus ->
                koolCanvas.isFocusable = !hasFocus
                gameCanvas?.isFocusable = !hasFocus && !singleWindowCapture
            },
            restoreFocus = {
                restoreOverlayKeyboard()
                focusVisibleCanvas()
            },
            returnKeysToCanvas = {
                restoreOverlayKeyboard()
                focusVisibleCanvas()
            },
        )
        PlatformTextInputBridge.install(textInputController)
        EmojiRasterizerBridge.install(emojiRasterizer)
        gameCanvas?.let { canvas ->
            canvas.name = GAME_CARD
            canvas.background = Color.BLACK
            canvas.isFocusable = !singleWindowCapture
            canvas.ignoreRepaint = true
            canvas.isVisible = false
            panel.add(canvas, GAME_CARD)
        }
        if (singleWindowCapture) {
            panel.add(koolCanvas, KOOL_CARD)
            panel.setComponentZOrder(koolCanvas, 0)
        }
        panel.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                resizeCanvases()
                fitInitialContentSize()
            }
        })

        overlayWindow?.let { configureKoolOverlayWindow(it, overlayPanel, koolCanvas) }

        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.isUndecorated = startupFullscreen || System.getenv("RWX_BENCHMARK_UNITS") != null
        frame.background = Color.BLACK
        frame.contentPane.background = Color.BLACK
        frame.rootPane.background = Color.BLACK
        frame.layout = BorderLayout()
        frame.minimumSize = Dimension(800, 600)
        frame.add(panel, BorderLayout.CENTER)
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                requestClose()
            }
        })
        if (singleWindowCapture) {
            frame.addWindowFocusListener(object : WindowAdapter() {
                override fun windowLostFocus(e: WindowEvent) {
                    SlickCanvasHost.notifyHostFocusLost()
                    hostFocusLostHandler?.invoke()
                }

                override fun windowGainedFocus(e: WindowEvent) {
                    focusVisibleCanvas()
                }
            })
        }
        frame.addComponentListener(object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) {
                syncOverlayBounds(forceLocationRefresh = true)
            }

            override fun componentResized(e: ComponentEvent) {
                syncOverlayBounds(forceLocationRefresh = true)
            }
        })
        frame.pack()
        if (startupFullscreen) {
            applyStartupFullscreen()
        } else {
            frame.setLocationRelativeTo(null)
        }
        frame.isVisible = true
        resizeCanvases()
        showKool()
        DebugResizeProbe.scheduleResize(frame)
        DebugResizeProbe.log("startup panel=${panel.width}x${panel.height} frame=${frame.width}x${frame.height}")
    }

    fun showGame(koolOverlay: Boolean = false) {
        val canvas = gameCanvas
        if (canvas == null) {
            // The Kool renderer draws the game into the only canvas this host owns.
            dispatchCanvasVisibilityChange(false) {
                resizeCanvases()
                setKoolOverlayVisible(true)
                if (!PlatformTextInputBridge.isEditing()) {
                    koolCanvas.requestFocusInWindow()
                }
            }
            return
        }
        val action = {
            resizeCanvases()
            canvas.isVisible = true
            setKoolOverlayVisible(koolOverlay)
            ensureGameBufferStrategy()
            if (PlatformTextInputBridge.isEditing()) {
                // The hidden frame editor already owns the input method. Taking canvas focus
                // here would cancel composition and leave only pinyin in the field.
            } else if (singleWindowCapture || koolOverlay) {
                koolCanvas.requestFocusInWindow()
            } else {
                canvas.requestFocusInWindow()
            }
            Unit
        }
        // The first show must wait for the EDT to create the JAWT peer before Slick starts. Later
        // calls can originate inside AWTGLCanvas.runInContext(), which holds the AWT lock; waiting
        // for the EDT there deadlocks when setVisible() tries to acquire that same lock.
        dispatchCanvasVisibilityChange(canvas.isShowing, action)
    }

    fun showKool() {
        val canvas = gameCanvas
        if (canvas == null) {
            dispatchCanvasVisibilityChange(false) {
                resizeCanvases()
                if (singleWindowCapture) setInGamePointerCursorActive(false)
                setKoolOverlayVisible(true)
                koolCanvas.requestFocusInWindow()
            }
            return
        }
        dispatchCanvasVisibilityChange(canvas.isShowing) {
            resizeCanvases()
            canvas.isVisible = false
            if (singleWindowCapture) setInGamePointerCursorActive(false)
            setKoolOverlayVisible(true)
            koolCanvas.requestFocusInWindow()
        }
    }

    fun requestClose() {
        // Provided Swing canvases have a no-op KoolWindow.close(), so desktop exit closes the subsystem directly.
        if (!closeRequested.compareAndSet(false, true)) return
        hostFocusLostHandler = null
        val bridge=getKoin().get<PlatformBridge>()
        bridge.filePickerHost=null
        keyboardFocusManager.removeKeyEventDispatcher(koolTypedControlCharacterFilter)
        PlatformTextInputBridge.uninstall(textInputController)
        EmojiRasterizerBridge.uninstall(emojiRasterizer)
        textInputController.dispose()
        // Stop the embedded Slick render thread and wait for it to release the game canvas'
        // JAWT drawing surface before disposing the window. Disposing while that thread still
        // holds the surface races the EDT inside JAWT_FreeDrawingSurface and crashes the JVM.
        // Run off the EDT so joining the render thread cannot deadlock its invokeAndWait calls.
        launchOnIO("shutdown-slick") {
            runCatching { SlickCanvasHost.shutdownRenderer() }
            windowSubsystem.close {
                SwingUtilities.invokeLater {
                    overlayWindow?.dispose()
                    frame.dispose()
                    exitProcess(0)
                }
            }
        }
    }

    fun setHostFocusLostHandler(handler: (() -> Unit)?) {
        hostFocusLostHandler = handler
    }

    /** Opt-in acceptance probe. The provided canvas controls the unmanaged Vulkan loop's visibility. */
    internal fun scheduleVisibilityProbe(context: de.fabmax.kool.platform.Lwjgl3Context) {
        val hideAfter = System.getenv("RWX_DEBUG_HIDE_AFTER_SECONDS")?.toLongOrNull()?.takeIf { it > 0 }
            ?.coerceAtMost(3600) ?: return
        val hideDuration = System.getenv("RWX_DEBUG_HIDE_SECONDS")?.toLongOrNull()?.coerceIn(1, 60) ?: 10
        val timer = java.util.Timer("RWX-window-visibility-probe", true)
        context.onShutdown += { timer.cancel() }
        val windowClass = context.window.javaClass.name
        val subsystemClass = context.windowSubsystem.javaClass.name
        logger.info { "RWX visibility probe scheduled: window=$windowClass subsystem=$subsystemClass " +
            "hideAfterSeconds=$hideAfter hiddenSeconds=$hideDuration at ${System.nanoTime()}" }
        var previousCanvasVisible = true
        var previousGameVisible = false
        var previousOverlayVisible = false
        var previousFrameVisible = true
        io.github.rwx.debug.WindowVisibilityProbeSchedule(
            after = { delayMillis, task ->
                timer.schedule(object : java.util.TimerTask() { override fun run() = task() }, delayMillis)
            },
            onWindowOwner = { task -> SwingUtilities.invokeLater(task) },
            setVisible = { visible ->
                check(SwingUtilities.isEventDispatchThread())
                if (!visible) {
                    previousCanvasVisible = koolCanvas.isVisible
                    previousGameVisible = gameCanvas?.isVisible ?: false
                    previousOverlayVisible = overlayWindow?.isVisible ?: false
                    previousFrameVisible = frame.isVisible
                    // Hiding only JFrame leaves CanvasWrapper.flags.isVisible true. Its component
                    // listener must see the actual render canvas hide before the Vulkan loop stops.
                    koolCanvas.isVisible = false
                    gameCanvas?.isVisible = false
                    overlayWindow?.isVisible = false
                    frame.isVisible = false
                } else {
                    frame.isVisible = previousFrameVisible
                    koolCanvas.isVisible = previousCanvasVisible
                    gameCanvas?.isVisible = previousGameVisible
                    overlayWindow?.isVisible = previousOverlayVisible
                    focusVisibleCanvas()
                }
                logger.info { "RWX visibility probe: visible=$visible window=$windowClass subsystem=$subsystemClass " +
                    "canvasVisible=${koolCanvas.isVisible} canvasShowing=${koolCanvas.isShowing} " +
                    "frameVisible=${frame.isVisible} at ${System.nanoTime()}" }
            },
            isClosed = closeRequested::get,
            cancel = timer::cancel,
            requested = { visible -> logger.info { "RWX visibility probe requested: visible=$visible at ${System.nanoTime()}" } },
        ).start(hideAfter * 1000, hideDuration * 1000)
    }

    override fun openFilePicker(
        title: String,
        allowedExtensions: Set<String>,
        allowDirectories: Boolean,
        onResult: (PlatformFileSelection?) -> Unit,
    ) {
        runOnEdt {
            val selection = runCatching {
                val extensions = allowedExtensions
                    .map { it.trim().removePrefix(".") }
                    .filter { it.isNotEmpty() }
                    .sorted()
                    .toTypedArray()
                val chooser = JFileChooser().apply {
                    dialogTitle = title
                    fileSelectionMode = if (allowDirectories) {
                        JFileChooser.FILES_AND_DIRECTORIES
                    } else {
                        JFileChooser.FILES_ONLY
                    }
                    isMultiSelectionEnabled = false
                    if (extensions.isNotEmpty()) {
                        val extensionList = extensions.joinToString { ".$it" }
                        fileFilter = FileNameExtensionFilter("Supported files ($extensionList)", *extensions)
                        isAcceptAllFileFilterUsed = false
                    }
                }
                chooser.takeIf {
                    it.showOpenDialog(if (singleWindowCapture) frame else overlayWindow) == JFileChooser.APPROVE_OPTION
                }
                    ?.selectedFile
                    ?.absoluteFile
                    ?.let { file -> PlatformFileSelection(path = file.path) }
            }.getOrNull()
            FrontendScope.launch { onResult(selection) }
        }
    }

    private fun resizeCanvases() {
        val width = panel.width.coerceAtLeast(1)
        val height = panel.height.coerceAtLeast(1)
        panel.revalidate()
        panel.doLayout()
        val canvas = gameCanvas
        if (canvas != null) {
            canvas.setBounds(0, 0, width, height)
            canvas.setSize(width, height)
        }
        if (singleWindowCapture) {
            koolCanvas.setBounds(0, 0, width, height)
        } else {
            koolCanvas.setSize(width, height)
            syncOverlayBounds()
        }
        if (canvas != null) {
            SlickCanvasHost.notifyGameCanvasResized(width, height)
        }
    }

    private fun restoreOverlayKeyboard() {
        overlayWindow?.focusableWindowState = true
        koolCanvas.isFocusable = true
        gameCanvas?.isFocusable = !singleWindowCapture
    }

    private fun focusVisibleCanvas() {
        if (PlatformTextInputBridge.isEditing() && textInputController.ownsCaret) {
            return
        }
        if (!isApplicationActive()) return
        if (singleWindowCapture) {
            koolCanvas.requestFocusInWindow()
        } else if (overlayWindow?.isVisible == true) {
            raiseOverlayIfActive()
            koolCanvas.requestFocusInWindow()
        } else if (gameCanvas?.isShowing == true) {
            gameCanvas.requestFocusInWindow()
        }
    }

    private fun setKoolOverlayVisible(visible: Boolean) {
        if (singleWindowCapture) {
            // Kool is the sole presented surface; Slick still renders into its showing canvas.
            koolCanvas.isVisible = true
            return
        }
        koolCanvas.isVisible = visible
        if (visible) {
            syncOverlayBounds(forceLocationRefresh = true)
            checkNotNull(overlayWindow).isVisible = true
            raiseOverlayIfActive()
        } else {
            checkNotNull(overlayWindow).isVisible = false
        }
    }

    private fun raiseOverlayIfActive() {
        if (overlayWindow?.isVisible == true && isApplicationActive()) {
            overlayWindow.toFront()
        }
    }

    private fun isApplicationActive(): Boolean =
        frame.isActive || overlayWindow?.isActive == true

    private fun syncOverlayBounds(forceLocationRefresh: Boolean = false) {
        val overlayWindow = overlayWindow ?: return
        if (!panel.isShowing) {
            cachedOverlayLocation = null
            return
        }
        val location = if (forceLocationRefresh || cachedOverlayLocation == null) {
            runCatching { panel.locationOnScreen }.getOrNull()?.also {
                cachedOverlayLocation = Point(it)
            } ?: return
        } else {
            cachedOverlayLocation!!
        }
        val width = panel.width.coerceAtLeast(1)
        val height = panel.height.coerceAtLeast(1)
        val boundsChanged =
            overlayWindow.x != location.x ||
                overlayWindow.y != location.y ||
                overlayWindow.width != width ||
                overlayWindow.height != height
        val canvasSizeChanged = koolCanvas.width != width || koolCanvas.height != height
        if (boundsChanged) {
            overlayWindow.setBounds(location.x, location.y, width, height)
        }
        if (canvasSizeChanged) {
            koolCanvas.setSize(width, height)
        }
        if (boundsChanged || canvasSizeChanged) {
            overlayWindow.validate()
        }
    }

    private fun fitInitialContentSize() {
        if (startupFullscreen) return
        if (!initialContentFitPending || applyingInitialContentFit || !frame.isShowing) return
        val preferred = panel.preferredSize
        val needsFit = panel.width < preferred.width || panel.height < preferred.height
        if (!needsFit) {
            if (initialContentFitApplied) {
                initialContentFitPending = false
            }
            return
        }

        val insets = frame.insets
        if (insets.top == 0 && insets.left == 0 && insets.bottom == 0 && insets.right == 0) return
        val targetWidth = preferred.width + insets.left + insets.right
        val targetHeight = preferred.height + insets.top + insets.bottom
        if (frame.width == targetWidth && frame.height == targetHeight) return

        applyingInitialContentFit = true
        initialContentFitApplied = true
        frame.setSize(targetWidth, targetHeight)
        frame.setLocationRelativeTo(null)
        applyingInitialContentFit = false
    }

    private fun ensureGameBufferStrategy() {
        if (singleWindowCapture) return
        val canvas = gameCanvas ?: return
        if (gameBufferStrategyCreated || !canvas.isDisplayable) return
        runCatching {
            canvas.createBufferStrategy(2)
            gameBufferStrategyCreated = true
        }
    }

    fun setInGamePointerCursorActive(active: Boolean) {
        runOnEdt {
            val cursorCanvas = koolCanvas as? PointerCursorCanvas
            cursorCanvas?.inGamePointerCursorActive = active
            if (active) {
                applyPointerCursor(koolCanvas)
            } else {
                koolCanvas.cursor = Cursor.getDefaultCursor()
            }
        }
    }

    private fun applyPointerCursor(vararg canvases: Canvas) {
        val pointerCursor = pointerCursor ?: createPointerCursor()?.also { pointerCursor = it } ?: return
        canvases.forEach { canvas ->
            (canvas as? PointerCursorCanvas)?.pointerCursor = pointerCursor
            canvas.cursor = pointerCursor
        }
    }

    private fun createPointerCursor(): Cursor? =
        runCatching {
            val imageFile = DesktopPlatformStorage.resolveAssetRoot().resolve("drawable/pointer.png")
            val image = ImageIO.read(imageFile) ?: return null
            Toolkit.getDefaultToolkit().createCustomCursor(image, Point(0, 0), "rwx-pointer")
        }.getOrNull()

    private fun applyStartupFullscreen() {
        val bounds = fullscreenBounds()
        frame.extendedState = JFrame.MAXIMIZED_BOTH
        frame.bounds = bounds
        panel.preferredSize = Dimension(bounds.width, bounds.height)
        panel.setSize(bounds.width, bounds.height)
    }

    companion object {
        val DEFAULT_WINDOW_SIZE = Vec2i(1280, 720)
        private const val KOOL_CARD = "kool"
        private const val GAME_CARD = "game"

        fun create(
            fullscreen: Boolean,
            useOpenGl: Boolean,
            singleWindowCapture: Boolean = false,
            useSlickCanvas: Boolean = true,
        ): SwingKoolHost {
            System.setProperty("org.lwjgl.opengl.contextAPI", "native")
            val koolCanvas = if (useOpenGl) {
                KoolGlCanvas(
                    GLData().apply {
                        alphaSize = if (singleWindowCapture) 0 else 8
                        depthSize = 24
                        stencilSize = 8
                        samples = 4
                        // A vsync swap would block while holding the AWT lock; frames are paced
                        // by PacedSwingWindowSubsystem instead.
                        swapInterval = 0
                    },
                )
            } else {
                PointerCursorCanvas()
            }
            val gameCanvas = if (useSlickCanvas) {
                SlickAwtGLCanvas(
                    data = GLData().apply {
                        alphaSize = 8
                        depthSize = 24
                        stencilSize = 8
                    },
                    requestedSwapInterval = 0,
                    offscreenOnMac = singleWindowCapture,
                )
            } else {
                null
            }
            lateinit var host: SwingKoolHost
            val subsystem = PacedSwingWindowSubsystem(
                SwingWindowSubsystem(
                    providedCanvas = koolCanvas,
                    makeFocusable = true,
                ),
            )
            host = SwingKoolHost(
                koolCanvas = koolCanvas,
                gameCanvas = gameCanvas,
                windowSubsystem = subsystem,
                startupFullscreen = fullscreen,
                singleWindowCapture = singleWindowCapture,
            )
            if (gameCanvas != null) {
                SlickCanvasHost.install(
                    canvasProvider = { host.gameCanvas },
                    visibilityController = { visible, koolOverlay ->
                        if (visible) host.showGame(koolOverlay) else host.showKool()
                    },
                    preferKoolCanvasFocus = singleWindowCapture,
                    pointerCursorController = if (singleWindowCapture) host::setInGamePointerCursorActive else null,
                )
            }
            return host
        }

        private fun windowTitle(): String =
            System.getProperty("rwx.windowTitle")
                ?.takeIf { it.isNotBlank() }
                ?: System.getenv("RWX_WINDOW_TITLE")?.takeIf { it.isNotBlank() }
                ?: "RWX Game"

        private fun runOnEdt(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) {
                action()
            } else {
                SwingUtilities.invokeLater(action)
            }
        }

        private fun initialWindowSize(fullscreen: Boolean): Vec2i {
            val requestedWidth = System.getenv("RWX_WINDOW_WIDTH")?.toIntOrNull()
            val requestedHeight = System.getenv("RWX_WINDOW_HEIGHT")?.toIntOrNull()
            if (requestedWidth != null && requestedHeight != null) {
                require(requestedWidth in 800..8192 && requestedHeight in 600..8192) { "Invalid diagnostic window size" }
                return Vec2i(requestedWidth, requestedHeight)
            }
            if (!fullscreen) return DEFAULT_WINDOW_SIZE
            val bounds = fullscreenBounds()
            return Vec2i(bounds.width.coerceAtLeast(800), bounds.height.coerceAtLeast(600))
        }

        private fun fullscreenBounds() =
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice
                .defaultConfiguration
                .bounds

    }
}

internal fun configureKoolOverlayWindow(
    overlayWindow: JWindow,
    overlayPanel: JPanel,
    koolCanvas: Canvas,
    osName: String = System.getProperty("os.name"),
) {
    overlayPanel.background = TransparentCanvasColor
    overlayPanel.isOpaque = false
    overlayPanel.add(koolCanvas, BorderLayout.CENTER)
    overlayWindow.background = if (osName.startsWith("Windows", ignoreCase = true)) {
        WindowsOverlayBackgroundColor
    } else {
        TransparentCanvasColor
    }
    overlayWindow.contentPane = overlayPanel
    overlayWindow.rootPane.isOpaque = false
    overlayWindow.focusableWindowState = true
    overlayWindow.enableInputMethods(true)
}

private val TransparentCanvasColor = Color(0, 0, 0, 0)
private val WindowsOverlayBackgroundColor = Color(0, 0, 0, 1)

internal fun dispatchCanvasVisibilityChange(
    gameCanvasShowing: Boolean,
    action: () -> Unit,
) {
    if (SwingUtilities.isEventDispatchThread()) {
        action()
    } else if (gameCanvasShowing) {
        SwingUtilities.invokeLater(action)
    } else {
        SwingUtilities.invokeAndWait(action)
    }
}

internal fun shouldSuppressKoolTypedCharacter(event: KeyEvent): Boolean =
    event.id == KeyEvent.KEY_TYPED && event.keyChar.isISOControl()

private class PointerCursorCanvas : Canvas() {
    var pointerCursor: Cursor? = null
    var inGamePointerCursorActive: Boolean = false

    override fun setCursor(cursor: Cursor?) {
        if (inGamePointerCursorActive && cursor?.type == Cursor.DEFAULT_CURSOR) {
            super.setCursor(pointerCursor ?: cursor)
        } else {
            super.setCursor(cursor)
        }
    }
}
