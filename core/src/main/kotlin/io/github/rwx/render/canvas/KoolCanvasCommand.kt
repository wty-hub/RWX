package io.github.rwx.render.canvas

/** Explicit display-only semantics. Unknown and mod drawing stays [Generic]. */
enum class KoolCanvasDrawRole { Generic, SelectionRing, Waypoint, UnitShadow, PerformanceHud }

data class KoolCanvasState(
    val transform: KoolCanvasTransform = KoolCanvasTransform.Identity,
    val clip: KoolCanvasRect? = null,
    val renderTarget: KoolCanvasRenderTargetId? = null,
    val drawRole: KoolCanvasDrawRole = KoolCanvasDrawRole.Generic,
    val semanticUnitId: Long = -1L,
) {
    companion object {
        val Default: KoolCanvasState = KoolCanvasState()
    }
}

sealed interface KoolCanvasCommand {
    data class Clear(
        val color: KoolCanvasColor,
        val blendMode: KoolCanvasBlendMode = KoolCanvasBlendMode.SourceOver,
        val renderTarget: KoolCanvasRenderTargetId? = null,
    ) : KoolCanvasCommand

    data class DrawTexture(
        val texture: KoolCanvasTextureRef,
        val source: KoolCanvasRect,
        val destination: KoolCanvasRect,
        val paint: KoolCanvasPaint,
        val state: KoolCanvasState,
    ) : KoolCanvasCommand {
        val sourceIsFullTexture: Boolean =
            source.left == 0f &&
                    source.top == 0f &&
                    source.right == texture.widthFloat &&
                    source.bottom == texture.heightFloat
        val sourceU0: Float = if (sourceIsFullTexture) 0f else source.left * texture.inverseSafeWidth
        val sourceV0: Float = if (sourceIsFullTexture) 0f else source.top * texture.inverseSafeHeight
        val sourceU1: Float = if (sourceIsFullTexture) 1f else source.right * texture.inverseSafeWidth
        val sourceV1: Float = if (sourceIsFullTexture) 1f else source.bottom * texture.inverseSafeHeight
    }

    data class DrawRect(
        val rect: KoolCanvasRect,
        val paint: KoolCanvasPaint,
        val state: KoolCanvasState,
    ) : KoolCanvasCommand

    data class DrawLine(
        val start: KoolCanvasPoint,
        val end: KoolCanvasPoint,
        val paint: KoolCanvasPaint,
        val state: KoolCanvasState,
    ) : KoolCanvasCommand

    data class DrawCircle(
        val center: KoolCanvasPoint,
        val radius: Float,
        val paint: KoolCanvasPaint,
        val state: KoolCanvasState,
    ) : KoolCanvasCommand {
        init {
            require(radius >= 0f) { "Circle radius must be non-negative" }
        }
    }

    data class DrawText(
        val text: String,
        val baseline: KoolCanvasPoint,
        val paint: KoolCanvasPaint,
        val state: KoolCanvasState,
    ) : KoolCanvasCommand
}

data class KoolCanvasFrame(
    val viewport: KoolCanvasViewport,
    val commands: List<KoolCanvasCommand>,
    val visualStats: KoolCanvasVisualStats? = null,
)

data class KoolCanvasVisualStats(
    val selectedUnits: Int,
    val visibleUnits: Int,
    val adaptiveBattleVisuals: Boolean = false,
)

fun interface KoolCanvasRenderer {
    fun render(frame: KoolCanvasFrame)
}
