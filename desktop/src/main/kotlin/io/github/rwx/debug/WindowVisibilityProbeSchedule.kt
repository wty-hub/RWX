package io.github.rwx.debug

/**
 * Diagnostic-only scheduling. Timers request work; only the window owner applies it.
 * Restoration starts after an applied hide and never depends on a rendered frame.
 */
internal class WindowVisibilityProbeSchedule(
    private val after: (Long, () -> Unit) -> Unit,
    private val onWindowOwner: (() -> Unit) -> Unit,
    private val setVisible: (Boolean) -> Unit,
    private val isClosed: () -> Boolean,
    private val cancel: () -> Unit,
    private val requested: (Boolean) -> Unit,
) {
    fun start(hideAfterMillis: Long, hiddenMillis: Long) {
        require(hideAfterMillis > 0 && hiddenMillis > 0)
        after(hideAfterMillis) {
            requested(false)
            onWindowOwner {
                if (isClosed()) {
                    cancel()
                } else {
                    setVisible(false)
                    after(hiddenMillis) {
                        requested(true)
                        onWindowOwner {
                            if (!isClosed()) setVisible(true)
                            cancel()
                        }
                    }
                }
            }
        }
    }
}
