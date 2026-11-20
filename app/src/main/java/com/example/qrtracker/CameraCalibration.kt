
package com.example.qrtracker

import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point3
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.core.MatOfDouble
import org.opencv.core.CvType

class CameraCalibration {

    companion object {
        const val BOARD_WIDTH = 9
        const val BOARD_HEIGHT = 6
        const val SQUARE_SIZE = 0.025
    }

    private val objectPoints = mutableListOf<Mat>()
    private val imagePoints = mutableListOf<Mat>()

    val cameraMatrix = Mat()
    val distortionCoefficients = MatOfDouble()

    private val boardSize = Size(
        BOARD_WIDTH.toDouble(),
        BOARD_HEIGHT.toDouble()
    )

    fun addImage(grayImage: Mat): Boolean {

        val corners = MatOfPoint2f()

        val found = Calib3d.findChessboardCorners(
            grayImage,
            boardSize,
            corners
        )

        if (!found) {
            corners.release()
            return false
        }

        Imgproc.cornerSubPix(
            grayImage,
            corners,
            Size(11.0, 11.0),
            Size(-1.0, -1.0),
            org.opencv.core.TermCriteria(
                org.opencv.core.TermCriteria.EPS or
                        org.opencv.core.TermCriteria.MAX_ITER,
                30,
                0.001
            )
        )

        val points3D = mutableListOf<Point3>()

        for (y in 0 until BOARD_HEIGHT) {
            for (x in 0 until BOARD_WIDTH) {
                points3D.add(
                    Point3(
                        x * SQUARE_SIZE,
                        y * SQUARE_SIZE,
                        0.0
                    )
                )
            }
        }

        objectPoints.add(MatOfPoint3f(*points3D.toTypedArray()))
        imagePoints.add(MatOfPoint2f(*corners.toArray()))

        corners.release()

        return true
    }

    fun calibrate(imageSize: Size): Double {

        return Calib3d.calibrateCamera(
            objectPoints,
            imagePoints,
            imageSize,
            cameraMatrix,
            distortionCoefficients,
            mutableListOf(),
            mutableListOf()
        )
    }

    fun imageCount(): Int = imagePoints.size

    fun useApproximateCalibration(imageSize: Size) {

        // Champ de vision horizontal supposé : 65 degrés
        val fov = Math.toRadians(65.0)

        val width = imageSize.width
        val height = imageSize.height

        val focalLength = width / (2.0 * kotlin.math.tan(fov / 2.0))

        val cx = width / 2.0
        val cy = height / 2.0

        cameraMatrix.create(3, 3, CvType.CV_64F)
        cameraMatrix.put(0, 0, focalLength)
        cameraMatrix.put(0, 1, 0.0)
        cameraMatrix.put(0, 2, cx)

        cameraMatrix.put(1, 0, 0.0)
        cameraMatrix.put(1, 1, focalLength)
        cameraMatrix.put(1, 2, cy)

        cameraMatrix.put(2, 0, 0.0)
        cameraMatrix.put(2, 1, 0.0)
        cameraMatrix.put(2, 2, 1.0)

        // Distorsion supposée nulle
        distortionCoefficients.fromArray(
            0.0, 0.0, 0.0, 0.0, 0.0
        )
    }
}
