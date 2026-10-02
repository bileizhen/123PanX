package io.github.bileizhen.pan123x.ui.util

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The highlight light is driven by a ~50 Hz sensor stream, so the reducer must return a stable
 * value while the device is at rest: any change would invalidate composition and keep Compose busy.
 */
class TiltLightDirectionTest {
    private val direction = TiltLightDirection()

    @Test
    fun flatDeviceKeepsLightPointingUpWithoutChanging() {
        val first = direction.onTilt(0f, 0f)
        assertEquals(TiltLightDirection.FLAT_ANGLE, first, 0f)
        repeat(50) { step ->
            val noise = (step % 5 - 2) * 0.01f
            assertEquals(first, direction.onTilt(noise, noise * 0.5f), 0f)
        }
    }

    @Test
    fun tiltBelowEnterThresholdIsIgnored() {
        assertEquals(TiltLightDirection.FLAT_ANGLE, direction.onTilt(0.12f, 0f), 0f)
    }

    @Test
    fun quantisedDirectionFollowsGravity() {
        assertEquals(0f, direction.onTilt(0.9f, 0f), 1e-6f)
        val quarterTurn = direction.onTilt(0f, 0.9f)
        assertEquals((PI / 2).toFloat(), quarterTurn, 1e-6f)
    }

    @Test
    fun reducesFiftyHertzNoiseToASingleValue() {
        val stable = direction.onTilt(0.6f, 0.35f)
        val jittered = (0 until 40).map { step ->
            val noise = (step % 7 - 3) * 0.002f
            direction.onTilt(0.6f + noise, 0.35f - noise)
        }
        assertEquals(setOf(stable), jittered.toSet())
    }

    @Test
    fun meaningfulTiltChangesTheValue() {
        val upright = direction.onTilt(0.7f, 0f)
        val rotated = direction.onTilt(0f, 0.7f)
        assertNotEquals(upright, rotated)
    }

    @Test
    fun hysteresisKeepsDirectionBetweenEnterAndExitThresholds() {
        val directional = direction.onTilt(0.9f, 0f)
        // |g_xy| = 0.1 sits between the exit (0.08) and enter (0.15) thresholds.
        assertEquals(directional, direction.onTilt(0.1f, 0f), 0f)
        // |g_xy| = 0.05 is below the exit threshold: back to the flat light.
        assertEquals(TiltLightDirection.FLAT_ANGLE, direction.onTilt(0.05f, 0f), 0f)
    }
}
