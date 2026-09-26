package io.github.rwx

import org.slf4j.LoggerFactory
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout

/**
 * Java AWT talks to input methods through XIM. fcitx5 on Wayland serves native clients over
 * text-input-v3 and often leaves [XMODIFIERS] unset, so an XWayland Swing field never sees the IM.
 *
 * This must run before the AWT toolkit is created. It only fills empty variables; an explicit
 * IBus/other setup is left alone.
 */
internal object LinuxInputMethodBootstrap {
    private val logger = LoggerFactory.getLogger("Desktop")

    val usesFcitx: Boolean
        get() = detectedFcitx

    private var detectedFcitx = false

    fun install() {
        if (!isLinux()) return
        detectedFcitx = detectFcitx()
        if (!detectedFcitx) return
        ensureEnv("XMODIFIERS", "@im=fcitx")
        ensureEnv("GTK_IM_MODULE", "fcitx")
        ensureEnv("QT_IM_MODULE", "fcitx")
        ensureEnv("SDL_IM_MODULE", "fcitx")
        if (System.getProperty(IM_STYLE_PROPERTY).isNullOrBlank()) {
            // on-the-spot preedit in OpenJDK's XIM client is unreliable with fcitx5.
            System.setProperty(IM_STYLE_PROPERTY, "below-the-spot")
        }
        logger.info(
            "fcitx5 input method: XMODIFIERS={} GTK_IM_MODULE={} java.awt.im.style={}",
            System.getenv("XMODIFIERS"),
            System.getenv("GTK_IM_MODULE"),
            System.getProperty(IM_STYLE_PROPERTY),
        )
    }

    private fun detectFcitx(): Boolean {
        val modifiers = System.getenv("XMODIFIERS").orEmpty()
        val gtk = System.getenv("GTK_IM_MODULE").orEmpty()
        if (modifiers.contains("ibus", ignoreCase = true) || gtk.contains("ibus", ignoreCase = true)) {
            return false
        }
        if (modifiers.contains("fcitx", ignoreCase = true) || gtk.contains("fcitx", ignoreCase = true)) {
            return true
        }
        return fcitx5ProcessRunning()
    }

    private fun fcitx5ProcessRunning(): Boolean =
        File("/proc").listFiles { file -> file.isDirectory && file.name.all(Char::isDigit) }
            ?.any { proc ->
                runCatching { File(proc, "comm").readText().trim() }.getOrNull() == "fcitx5"
            } == true

    private fun ensureEnv(name: String, value: String) {
        if (!System.getenv(name).isNullOrBlank()) return
        nativeSetenv(name, value)
        publishToJavaGetenv(name, value)
    }

    private fun nativeSetenv(name: String, value: String) {
        val linker = Linker.nativeLinker()
        val setenv = linker.downcallHandle(
            linker.defaultLookup().find("setenv").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
        )
        Arena.ofConfined().use { arena ->
            val result = setenv.invoke(arena.allocateFrom(name), arena.allocateFrom(value), 1) as Int
            if (result != 0) {
                logger.warn("setenv {} failed with {}", name, result)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun publishToJavaGetenv(name: String, value: String) {
        runCatching {
            val type = Class.forName("java.lang.ProcessEnvironment")
            val environment = type.getDeclaredField("theEnvironment").apply { isAccessible = true }
            (environment.get(null) as MutableMap<String, String>)[name] = value
        }
    }

    private fun isLinux(): Boolean =
        System.getProperty("os.name").orEmpty().contains("linux", ignoreCase = true)

    private const val IM_STYLE_PROPERTY = "java.awt.im.style"
}
