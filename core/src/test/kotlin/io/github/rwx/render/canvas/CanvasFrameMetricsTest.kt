package io.github.rwx.render.canvas

import com.corrodinggames.rts.gameFramework.graphics.GraphicsEngine
import kotlin.test.*

class CanvasFrameMetricsTest {
    @Test fun `new snapshots accepted presents and simulation ticks have distinct rates`() {
        val counter = CanvasFrameRateCounter(0, collectIntervals = true)
        repeat(20) { counter.produced(it, 1) }
        for ((index, sequence) in listOf(1L, 1L, 2L, 2L).withIndex()) {
            counter.presented(sequence, 1, index * 250_000_000L)
        }
        assertNull(counter.sample(999_999_999, 1_000_000_000))
        val sample = counter.sample(1_000_000_000, 1_000_000_000)!!
        assertEquals(4.0, sample.acceptedPresentFps)
        assertEquals(20.0, sample.producedSnapshotHz)
        assertEquals(2.0, sample.freshSnapshotHz)
        assertEquals(19.0, sample.simulationTicksPerSecond)
        assertEquals(.5, sample.repeatRatio)
        assertEquals(250.0, sample.p95Ms)
        counter.produced(20, 1)
        counter.presented(2, 1, 1_250_000_000)
        val repeated = counter.sample(2_000_000_000, 1_000_000_000)!!
        assertEquals(0.0, repeated.freshSnapshotHz)
        assertEquals(1.0, repeated.repeatRatio)
        assertEquals(1.0, repeated.simulationTicksPerSecond)
        counter.produced(0, 2)
        counter.presented(2, 2, 2_250_000_000)
        val restarted = counter.sample(3_000_000_000, 1_000_000_000)!!
        assertEquals(1.0, restarted.freshSnapshotHz)
        assertEquals(0.0, restarted.simulationTicksPerSecond)
    }

    @Test fun `FPS semantic scope is explicit and leaves other text unchanged`() {
        val recorder = KoolGraphicsEngine(KoolCanvasCpuTextureStore())
        recorder.beginFrame(1920, 1080)
        recorder.beginDrawRole(GraphicsEngine.DRAW_ROLE_PERFORMANCE_HUD, -1)
        recorder.a("255fps", 100f, 35f, null)
        recorder.endDrawRole()
        recorder.a("255fps", 100f, 85f, null)
        val commands = recorder.snapshot().commands.filterIsInstance<KoolCanvasCommand.DrawText>()
        assertEquals(KoolCanvasDrawRole.PerformanceHud, commands[0].state.drawRole)
        assertEquals(KoolCanvasDrawRole.Generic, commands[1].state.drawRole)
        val rates = CanvasFrameRateSample(1.0, 120.0, 255.0, 60.0, 60.0, .5)
        assertSame(commands[1], CanvasPerformanceHud.command(commands[1], rates))
        val displayed = CanvasPerformanceHud.command(commands[0], rates)
        assertEquals("render 120 FPS / new 60/s / engine 255/s / repeat 50%", displayed.text)
        assertEquals(commands[0].baseline, displayed.baseline)
        assertEquals(commands[0].paint, displayed.paint)
        assertEquals("255fps (engine)", CanvasPerformanceHud.command(commands[0], null).text)
        assertEquals("255fps", commands[0].text, "Published engine command stays immutable")
    }
}
