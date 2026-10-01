package io.github.rwx.kool

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/** One owner for the legacy engine; presentation never supplies its clock or waits for it. */
internal class EngineOwnerLoop(
    private val periodNanos: () -> Long,
    private val tick: (Float) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : AutoCloseable {
    private data class InputTransition(val identity: String, val down: Boolean)
    private data class Task(val transition: InputTransition?, val execute: () -> Unit)
    private val tasks = ConcurrentLinkedQueue<Task>()
    private val running = AtomicBoolean(true)
    private val queueGate = Any()
    private var resetClock = true
    private val thread = Thread(::run, "RWX-engine-owner").apply { isDaemon = true; start() }
    val isOwner: Boolean get() = Thread.currentThread() === thread

    fun resetClock() { check(isOwner); resetClock = true }

    fun <T> submit(action: () -> T): CompletableFuture<T> {
        return enqueue(null, action)
    }

    /** A down/up pair must each be observed by a legacy loop; FIFO execution alone is insufficient. */
    fun <T> submitInput(identity: String, down: Boolean, action: () -> T): CompletableFuture<T> =
        enqueue(InputTransition(identity, down), action)

    private fun <T> enqueue(transition: InputTransition?, action: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        val inline = isOwner
        synchronized(queueGate) {
            if (!running.get()) return future.apply { completeExceptionally(IllegalStateException("Engine is closed")) }
            if (!inline) {
                tasks.add(Task(transition) { complete(future, action) })
                LockSupport.unpark(thread)
            }
        }
        if (inline) complete(future, action)
        return future
    }

    fun <T> call(action: () -> T): T = if (isOwner) action() else submit(action).get()

    private fun <T> complete(future: CompletableFuture<T>, action: () -> T) {
        try { future.complete(action()) } catch (error: Throwable) { future.completeExceptionally(error) }
    }

    private fun run() {
        var previous = System.nanoTime()
        var deadline = previous
        val inputStates = mutableMapOf<String, Boolean>()
        val transitionsObserved = mutableSetOf<String>()
        while (running.get() || tasks.isNotEmpty()) {
            while (true) {
                val next = tasks.peek() ?: break
                val transition = next.transition
                if (running.get() && transition != null && inputStates[transition.identity] != transition.down &&
                    transition.identity in transitionsObserved) break
                tasks.poll()
                if (transition != null) {
                    inputStates[transition.identity] = transition.down
                    transitionsObserved += transition.identity
                }
                next.execute()
            }
            if (!running.get()) break
            val now = System.nanoTime()
            if (resetClock) { previous = now; deadline = now; resetClock = false }
            if (now < deadline) { LockSupport.parkNanos(this, deadline - now); continue }
            val delta = ((now - previous).coerceAtLeast(0L) / 1_000_000_000.0).toFloat()
            previous = now
            try { tick(delta) } catch (error: Throwable) { onFailure(error) }
            transitionsObserved.clear()
            // This is the original outer throttle, not a fixed simulation accumulator.
            deadline = now + periodNanos().coerceAtLeast(1L)
        }
    }

    override fun close() {
        synchronized(queueGate) { running.set(false) }
        LockSupport.unpark(thread)
        if (!isOwner) thread.join(5000)
    }
}
