package io.github.rwx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KoolMsaaSamplesTest {
    @Test fun defaultRetainsFourSamples() { assertEquals(4, desktopKoolMsaaSamples(null)) }

    @Test fun diagnosticSupportsOnlyOneTwoOrFourSamples() {
        for (samples in listOf(1, 2, 4)) assertEquals(samples, desktopKoolMsaaSamples(samples.toString()))
    }

    @Test fun malformedAndUnsupportedCountsAreRejected() {
        for (value in listOf("", "auto", "0", "-1", "3", "8", "2147483648")) {
            assertFailsWith<IllegalArgumentException>(value) { desktopKoolMsaaSamples(value) }
        }
    }
}
