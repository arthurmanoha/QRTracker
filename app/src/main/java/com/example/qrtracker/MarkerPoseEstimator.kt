package com.example.qrtracker

import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point3
import org.opencv.core.MatOfDouble

data class MarkerPose(
    val x: Double,
    val y: Double,
    val z: Double,
    val distance: Double,
    val roll: Double,
    val pitch: Double,
    val yaw: Double,
    val rotationMatrix: DoubleArray
)

class MarkerPoseEstimator {

    companion object {
        // Côté du carré noir extérieur : 10 cm
        const val MARKER_SIZE = 0.10
    }

    // Ordre imposé par SOLVEPNP_IPPE_SQUARE :
    // haut-gauche, haut-droite, bas-droite, bas-gauche.
    private val objectPoints = MatOfPoint3f(
        Point3(-MARKER_SIZE / 2, MARKER_SIZE / 2, 0.0),
        Point3(MARKER_SIZE / 2, MARKER_SIZE / 2, 0.0),
        Point3(MARKER_SIZE / 2, -MARKER_SIZE / 2, 0.0),
        Point3(-MARKER_SIZE / 2, -MARKER_SIZE / 2, 0.0)
    )

    fun estimate(
        imageCorners: Mat,
        cameraMatrix: Mat,
        distortionCoefficients: MatOfDouble
    ): MarkerPose? {

        if (cameraMatrix.empty() || distortionCoefficients.empty()) {
            return null
        }

        val imagePoints = MatOfPoint2f()
        val rotationVector = Mat()
        val translationVector = Mat()
        val rotationMatrix = Mat()

        try {
            // Recopie des quatre coins détectés
            val points = Array(4) { i ->
                val p = imageCorners.get(0, i)
                org.opencv.core.Point(p[0], p[1])
            }
            imagePoints.fromArray(*points)

            val success = Calib3d.solvePnP(
                objectPoints,
                imagePoints,
                cameraMatrix,
                distortionCoefficients,
                rotationVector,
                translationVector,
                false,
                Calib3d.SOLVEPNP_IPPE_SQUARE
            )

            if (!success) return null

            val x = translationVector.get(0, 0)[0]
            val y = translationVector.get(1, 0)[0]
            val z = translationVector.get(2, 0)[0]

            // Conversion du vecteur de rotation en matrice
            Calib3d.Rodrigues(rotationVector, rotationMatrix)

            val rotationValues = DoubleArray(9) { index ->
                rotationMatrix.get(index / 3, index % 3)[0]
            }

            // Extraction des éléments de la matrice
            val r00 = rotationMatrix.get(0, 0)[0]
            val r10 = rotationMatrix.get(1, 0)[0]
            val r20 = rotationMatrix.get(2, 0)[0]
            val r21 = rotationMatrix.get(2, 1)[0]
            val r22 = rotationMatrix.get(2, 2)[0]

            // Décomposition en angles d'Euler
            val roll = Math.atan2(r21, r22)

            val pitch = Math.atan2(
                -r20,
                kotlin.math.sqrt(r00 * r00 + r10 * r10)
            )

            val yaw = Math.atan2(r10, r00)

            // Conversion en degrés
            val rollDegrees = Math.toDegrees(roll)
            val pitchDegrees = Math.toDegrees(pitch)
            val yawDegrees = Math.toDegrees(yaw)

            val distance = kotlin.math.sqrt(x * x + y * y + z * z)

            // Un marqueur derrière la caméra n'est pas exploitable.
            if (z <= 0.0) return null

            return MarkerPose(
                x = x,
                y = y,
                z = z,
                distance = distance,
                roll = rollDegrees,
                pitch = pitchDegrees,
                yaw = yawDegrees,
                rotationMatrix = rotationValues
            )

        } finally {
            imagePoints.release()
            rotationVector.release()
            translationVector.release()
            rotationMatrix.release()
        }
    }

    fun release() {
        objectPoints.release()
    }
}
