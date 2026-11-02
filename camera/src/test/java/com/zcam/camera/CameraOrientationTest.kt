package com.zcam.camera

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOrientationTest {

    @Test
    fun landscape_sensor_angles_map_to_inverse_display_rotations() {
        assertEquals(
            Surface.ROTATION_270,
            targetRotationForOrientation(90, prefersLandscape = true, fallback = Surface.ROTATION_90)
        )
        assertEquals(
            Surface.ROTATION_90,
            targetRotationForOrientation(270, prefersLandscape = true, fallback = Surface.ROTATION_90)
        )
    }

    @Test
    fun incompatible_orientation_keeps_last_stable_rotation() {
        assertEquals(
            Surface.ROTATION_270,
            targetRotationForOrientation(0, prefersLandscape = true, fallback = Surface.ROTATION_270)
        )
    }
}
