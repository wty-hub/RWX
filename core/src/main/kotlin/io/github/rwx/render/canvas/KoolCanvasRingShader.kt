package io.github.rwx.render.canvas

import de.fabmax.kool.modules.ksl.KslShader
import de.fabmax.kool.modules.ksl.blocks.mvpMatrix
import de.fabmax.kool.modules.ksl.lang.*
import de.fabmax.kool.pipeline.PipelineConfig
import de.fabmax.kool.scene.VertexLayouts
import de.fabmax.kool.scene.instanceAttrib
import de.fabmax.kool.scene.vertexAttrib
import de.fabmax.kool.util.MemoryLayout
import de.fabmax.kool.util.Struct

internal object KoolCanvasRingInstanceLayout : Struct("CanvasRingInstance", MemoryLayout.TightlyPacked) {
    val rect = float4("ringRect")
    val props = float4("ringProps")
    val color = float4("ringColor")
    val clip = float4("ringClip")
}

internal class KoolCanvasRingShader(config: PipelineConfig) : KslShader(Model(), config) {
    private class Model : KslProgram("Canvas Selection Rings") {
        init {
            val local = interStageFloat2()
            val position = interStageFloat2()
            val props = interStageFloat4(interpolation = KslInterStageInterpolation.Flat)
            val color = interStageFloat4(interpolation = KslInterStageInterpolation.Flat)
            val clip = interStageFloat4(interpolation = KslInterStageInterpolation.Flat)
            vertexStage {
                main {
                    val corner = vertexAttrib(VertexLayouts.Position.position)
                    val rect = instanceAttrib(KoolCanvasRingInstanceLayout.rect)
                    val pos = float2Var(float2Value(rect.x + (rect.z - rect.x) * corner.x,
                        rect.y + (rect.w - rect.y) * corner.y))
                    position.input set pos
                    local.input set corner.xy * 2f.const - float2Value(1f, 1f)
                    props.input set instanceAttrib(KoolCanvasRingInstanceLayout.props)
                    color.input set instanceAttrib(KoolCanvasRingInstanceLayout.color)
                    clip.input set instanceAttrib(KoolCanvasRingInstanceLayout.clip)
                    outPosition set mvpMatrix().matrix * float4Value(pos, 0f.const, 1f.const)
                }
            }
            fragmentStage {
                main {
                    `if`(any(position.output lt clip.output.xy) or any(position.output gt clip.output.zw)) { discard() }
                    val distance = float1Var(abs(length(local.output) * props.output.x - props.output.y))
                    val alpha = float1Var(clamp(props.output.z + 0.5f.const - distance, 0f.const, 1f.const) * color.output.a)
                    colorOutput(color.output.rgb, alpha)
                }
            }
        }
    }
}
