package io.github.rwx.render.canvas

import de.fabmax.kool.modules.ksl.KslShader
import de.fabmax.kool.modules.ksl.blocks.mvpMatrix
import de.fabmax.kool.modules.ksl.lang.*
import de.fabmax.kool.pipeline.PipelineConfig
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.scene.VertexLayouts
import de.fabmax.kool.scene.instanceAttrib
import de.fabmax.kool.scene.vertexAttrib
import de.fabmax.kool.util.MemoryLayout
import de.fabmax.kool.util.Struct

internal object KoolCanvasSpriteInstanceLayout : Struct("CanvasSpriteInstance", MemoryLayout.TightlyPacked) {
    val originAxisX = float4("spriteOriginAxisX")
    val axisYModeAmount = float4("spriteAxisYModeAmount")
    val uv = float4("spriteUv")
    val tint = float4("spriteTint")
    val team = float4("spriteTeam")
    val clip = float4("spriteClip")
}

/** Team color is per instance, so consecutive bodies and turrets share one ordered draw. */
internal class KoolCanvasSpriteShader(pipelineConfig: PipelineConfig, additive: Boolean) :
    KslShader(Model(additive), pipelineConfig) {
    var colorMap: Texture2d? by texture2d("spriteMap")

    private class Model(additive: Boolean) : KslProgram("Canvas Ordered Sprites") {
        init {
            val uv = interStageFloat2()
            val position = interStageFloat2()
            val tint = interStageFloat4(interpolation = KslInterStageInterpolation.Flat)
            val team = interStageFloat4(interpolation = KslInterStageInterpolation.Flat)
            val modeAmount = interStageFloat2(interpolation = KslInterStageInterpolation.Flat)
            val clip = interStageFloat4(interpolation = KslInterStageInterpolation.Flat)
            vertexStage {
                main {
                    val corner = vertexAttrib(VertexLayouts.Position.position)
                    val origin = instanceAttrib(KoolCanvasSpriteInstanceLayout.originAxisX)
                    val axis = instanceAttrib(KoolCanvasSpriteInstanceLayout.axisYModeAmount)
                    val coords = instanceAttrib(KoolCanvasSpriteInstanceLayout.uv)
                    val pos = float2Var(origin.xy + origin.zw * corner.x + axis.xy * corner.y)
                    position.input set pos
                    uv.input set float2Value(coords.x + (coords.z - coords.x) * corner.x,
                        coords.y + (coords.w - coords.y) * corner.y)
                    tint.input set instanceAttrib(KoolCanvasSpriteInstanceLayout.tint)
                    team.input set instanceAttrib(KoolCanvasSpriteInstanceLayout.team)
                    modeAmount.input set axis.zw
                    clip.input set instanceAttrib(KoolCanvasSpriteInstanceLayout.clip)
                    outPosition set mvpMatrix().matrix * float4Value(pos, 0f.const, 1f.const)
                }
            }
            fragmentStage {
                main {
                    `if`(any(position.output lt clip.output.xy) or any(position.output gt clip.output.zw)) { discard() }
                    val tex = float4Var(sampleTexture(texture2d("spriteMap"), uv.output))
                    val source = float3Var(tex.rgb)
                    val rgb = float3Var(source)
                    val mode = modeAmount.output.x
                    `if`(mode eq 1f.const) {
                        `if`((source.g gt 0f.const) and (abs(source.r - source.b) le 0.04f.const)) {
                            rgb set float3Value(source.r, source.r, source.r) + team.output.rgb * (source.g - source.r)
                        }
                    }.`elseIf`(mode eq 2f.const) {
                        rgb set source + team.output.rgb * modeAmount.output.y
                    }.`elseIf`(mode eq 3f.const) {
                        val lo = float1Var(min(min(source.r, source.g), source.b))
                        val delta = float1Var(max(max(abs(source.r - source.g), abs(source.g - source.b)), abs(source.b - source.r)))
                        `if`(delta gt (15f / 256f).const) {
                            rgb set float3Value(lo, lo, lo) + team.output.rgb * delta
                        }
                    }
                    val alpha = float1Var(tex.a * tint.output.a)
                    if (additive) colorOutput(rgb * tint.output.rgb * alpha, alpha)
                    else colorOutput(rgb * tint.output.rgb, alpha)
                }
            }
        }
    }
}
