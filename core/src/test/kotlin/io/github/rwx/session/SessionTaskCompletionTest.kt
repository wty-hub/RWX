package io.github.rwx.session

import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwx.render.RendererMode
import io.github.rwx.render.canvas.KoolCanvasFrame
import io.github.rwx.render.canvas.KoolCanvasViewport
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.test.*

class SessionTaskCompletionTest {
    private class Session(private val independent: Boolean) : GameSession() {
        override val usesIndependentEngineLoop get() = independent
        override val rendererMode = object : RendererMode { override val id = "completion-test" }
        val queued = mutableListOf<() -> Unit>()
        override fun <T> submitSessionTask(action: () -> T): CompletableFuture<T> {
            if (!independent) return super.submitSessionTask(action)
            val future = CompletableFuture<T>()
            queued += { runCatching(action).onSuccess(future::complete).onFailure(future::completeExceptionally) }
            return future
        }
        fun runOwnerTasks() { while (queued.isNotEmpty()) queued.removeAt(0).invoke() }
        override fun loadPendingMapNow() = KoolCanvasFrame(KoolCanvasViewport(1, 1), emptyList())
        override fun ensureStarted(viewport: KoolCanvasViewport): GameEngine = error("Not needed")
        override fun applyViewport(engine: GameEngine, viewport: KoolCanvasViewport) = Unit
    }

    @Test fun `legacy default completes inline and returns actual false and null`() {
        val session = Session(false)
        val events = mutableListOf<String>()
        session.requestSessionTask({ events += "action"; false }) { result -> events += "callback:${result.getOrThrow()}" }
        assertEquals(listOf("action", "callback:false"), events)
        var result: Result<String?>? = null
        session.requestSessionTask<String?>({ null }) { result = it }
        assertTrue(result!!.isSuccess)
        assertNull(result!!.getOrThrow())
    }

    @Test fun `independent owner results reach UI only after completion drain in submission order`() {
        val session = Session(true)
        val executed = mutableListOf<Int>()
        val received = mutableListOf<Int>()
        repeat(20) { i -> session.requestSessionTask({ executed += i; i * 2 }) { received += it.getOrThrow() } }
        assertTrue(executed.isEmpty())
        assertTrue(received.isEmpty())
        session.runOwnerTasks()
        assertEquals((0 until 20).toList(), executed)
        assertTrue(received.isEmpty())
        session.drainSessionCompletions()
        assertEquals((0 until 20).map { it * 2 }, received)
        session.drainSessionCompletions()
        assertEquals(20, received.size)
    }

    @Test fun `domain exception keeps its message and cause across owner acknowledgement`() {
        val session = Session(true)
        val cause = IllegalArgumentException("original cause")
        val failure = IllegalStateException("Cannot adopt started map", cause)
        var received: Throwable? = null
        session.requestSessionTask<Unit>({ throw failure }) { received = it.exceptionOrNull() }
        session.runOwnerTasks()
        assertNull(received)
        session.drainSessionCompletions()
        assertSame(failure, received)
        assertSame(cause, received!!.cause)
    }

    @Test fun `future wrapper is removed without unwrapping the domain exception cause`() {
        val session = Session(true)
        val failure = IllegalStateException("Domain context", IllegalArgumentException("cause"))
        var received: Throwable? = null
        session.requestSessionTask<Unit>({ throw CompletionException(failure) }) { received = it.exceptionOrNull() }
        session.runOwnerTasks()
        session.drainSessionCompletions()
        assertSame(failure, received)
    }
}
