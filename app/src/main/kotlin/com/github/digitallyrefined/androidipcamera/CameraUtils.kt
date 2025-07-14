package com.github.digitallyrefined.androidipcamera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

object CameraUtils {
    fun getMaxSupportedFps(context: Context): Int {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull() ?: return 30
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return 30
        return ranges.maxOfOrNull { it.upper } ?: 30
    }

    fun getAvailableFpsOptions(context: Context): List<Int> {
        val maxFps = getMaxSupportedFps(context)
        val customFps = listOf(1, 2, 5, 10, 15, 30, 60)
        return customFps.filter { it <= maxFps }.distinct()
    }
}
