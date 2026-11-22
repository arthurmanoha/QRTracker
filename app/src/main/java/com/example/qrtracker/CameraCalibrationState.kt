package com.example.qrtracker

import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import java.util.concurrent.atomic.AtomicBoolean

object CameraCalibrationState {

    val ready = AtomicBoolean(false)

    var cameraMatrix: Mat? = null
    var distortionCoefficients: MatOfDouble? = null

    var imageWidth: Int = 0
    var imageHeight: Int = 0
}