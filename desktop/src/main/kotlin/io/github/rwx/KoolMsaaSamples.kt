package io.github.rwx

/** Opt-in rendering diagnostic; default quality is unchanged until visual acceptance is available. */
internal fun desktopKoolMsaaSamples(
    configured: String? = System.getProperty("rwx.kool.msaaSamples") ?: System.getenv("RWX_KOOL_MSAA_SAMPLES"),
): Int {
    if (configured == null) return 4
    val samples = configured.toIntOrNull()
    require(samples == 1 || samples == 2 || samples == 4) {
        "Kool MSAA diagnostic accepts only 1, 2 or 4 samples; received '$configured'"
    }
    return samples
}
