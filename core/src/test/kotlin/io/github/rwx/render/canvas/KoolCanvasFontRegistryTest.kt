package io.github.rwx.render.canvas

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.util.MsdfFont
import de.fabmax.kool.util.TextMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class KoolCanvasFontRegistryTest {
    private fun defaultFont(): MsdfFont {
        if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm())
        return MsdfFont.DEFAULT_FONT
    }

    @Test fun `detached CPU glyph metrics preserve MSDF widths including fixed width digits and lines`() {
        val font = defaultFont()
        KoolCanvasFontRegistry.installBaseFont(font)
        for (text in listOf("Tank x2000", "11\n888888", "", "abc Ω", "1A9z", "选择单位2000", "A\nB\n", "A  B\t9", "\u0001")) {
            val size = 18f
            val expected = if (text.isEmpty()) 0f else font.derive(size).textDimensions(text, TextMetrics()).baselineWidth
                .takeIf { it > 0f } ?: (text.length * size)
            assertEquals(expected.toRawBits(), KoolCanvasFontRegistry.textWidth(text, size).toRawBits())
        }
    }

    @Test fun `a delayed packet pins the font version through replacement and consumer retention`() {
        val base = defaultFont()
        try {
            KoolCanvasFontRegistry.installBaseFont(base.copy(weight = .12f))
            val store = KoolCanvasCpuTextureStore()
            val envelope = store.freezeFrame(KoolCanvasFrame(KoolCanvasViewport(1, 1), emptyList()), 1, 1, 1, 1)
            val oldVersion = envelope.resourceLease.fonts.baseVersion
            val retained = envelope.retain()
            envelope.close()
            KoolCanvasFontRegistry.installBaseFont(base.copy(weight = .34f))
            val nextSnapshot = KoolCanvasFontRegistry.snapshot()
            assertNotEquals(oldVersion, nextSnapshot.baseVersion)
            assertEquals(.12f, KoolCanvasFontRegistry.withSnapshot(retained.resourceLease.fonts) { KoolCanvasFontRegistry.font(20f).weight })
            assertEquals(.34f, KoolCanvasFontRegistry.font(20f).weight)
            retained.close()
            KoolCanvasFontRegistry.releaseSnapshot(nextSnapshot)
        } finally { KoolCanvasFontRegistry.installBaseFont(base) }
    }
}
