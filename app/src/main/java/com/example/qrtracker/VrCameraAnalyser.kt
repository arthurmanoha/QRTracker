
package com.example.qrtracker

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect
import java.util.concurrent.atomic.AtomicBoolean
import android.os.SystemClock

class VrCameraAnalyzer(
    private val onWorldMarkersDetected: (Map<Int, WorldMarkerPose>, Long) -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val dictionary =
        Objdetect.getPredefinedDictionary(
            Objdetect.DICT_APRILTAG_36h11
        )

    private val detectorParameters = DetectorParameters()

    private val detector = ArucoDetector(
        Objdetect.getPredefinedDictionary(
            Objdetect.DICT_APRILTAG_36h11
        ),
        DetectorParameters()
    )

    private val poseEstimator = MarkerPoseEstimator()

    private val processing = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {

        val currentTime = SystemClock.elapsedRealtime()

        val imageTimestamp = image.imageInfo.timestamp
        val nowTimestamp = SystemClock.elapsedRealtimeNanos()


        Log.d(
            "QRTracker2",
            "Horodatage image : $imageTimestamp ns - Âge de l'image : ${(nowTimestamp - imageTimestamp) / 1_000_000} ms"
        )

        if (!processing.compareAndSet(false, true)) {
            image.close()
            return
        }

        val gray = Mat()
        val ids = Mat()
        val corners = ArrayList<Mat>()
        val rejected = ArrayList<Mat>()

        var cameraMatrix: Mat? = null
        var distortion: org.opencv.core.MatOfDouble? = null

        cameraMatrix = Mat(3, 3, CvType.CV_64F)

        cameraMatrix!!.put(0, 0, 469.5001, 0.0, 320.0)
        cameraMatrix!!.put(1, 0, 0.0, 469.5000, 240.0)
        cameraMatrix!!.put(2, 0, 0.0, 0.0, 1.0)

        distortion = org.opencv.core.MatOfDouble(
            0.0, 0.0, 0.0, 0.0, 0.0
        )

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer

            val width = image.width
            val height = image.height
            Log.d(
                "QRTracker2",
                "CameraX : ${width}x${height}, " +
                        "rotation=${image.imageInfo.rotationDegrees}, " +
                        "crop=${image.cropRect}"
            )
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val base = buffer.position()

            val grayBytes = ByteArray(width * height)

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = base + y * rowStride + x * pixelStride
                    grayBytes[y * width + x] = buffer.get(index)
                }
            }

            gray.create(height, width, CvType.CV_8UC1)
            gray.put(0, 0, grayBytes)

            // Détection brute des marqueurs
            detector.detectMarkers(
                gray,
                corners,
                ids,
                rejected
            )

            // Affichage des identifiants détectés
            val detectedIds = mutableListOf<Int>()

            for (i in 0 until ids.rows()) {
                detectedIds.add(ids.get(i, 0)[0].toInt())
            }

            Log.d(
                "QRTracker2",
                "Détection brute : ${detectedIds.size} marqueur(s) : $detectedIds"
            )
            val poses = mutableMapOf<Int, MarkerPose>()

            for (i in 0 until ids.rows()) {

                val id = ids.get(i, 0)[0].toInt()

                val pose = poseEstimator.estimate(
                    corners[i],
                    cameraMatrix!!,
                    distortion!!
                )

                if (pose != null) {
                    poses[id] = pose

                    Log.d(
                        "QRTracker2",
                        "Marqueur $id : " +
                                "x=${pose.x}, y=${pose.y}, z=${pose.z}, " +
                                "distance=${pose.distance}"
                    )
                    Log.d(
                        "QRTracker2",
                        "Marqueur $id rotation : ${pose.rotationMatrix.contentToString()}"
                    )
                }
            }

            Log.d(
                "QRTracker2",
                "Poses calculées : ${poses.keys}"
            )

            // Calcul des positions dans le repère mondial
            val origin = poses[0]
            if (origin == null) {
                onWorldMarkersDetected(emptyMap(), imageTimestamp)
                return
            }

            val worldPoses = mutableMapOf<Int, WorldMarkerPose>()

            for ((id, pose) in poses) {
                worldPoses[id] = if (id == 0) {
                    // Marqueur 0 : conserver sa pose originale dans le repère caméra.
                    WorldMarkerPose(
                        x = pose.x,
                        y = pose.y,
                        z = pose.z,
                        rotationMatrix = pose.rotationMatrix
                    )
                } else {
                    // Autres marqueurs : coordonnées relatives au marqueur 0.
                    toWorldCoordinates(pose, origin)
                }
            }

            onWorldMarkersDetected(worldPoses, imageTimestamp)

            Log.d(
                "QRTracker2",
                "aaaaa Origine caméra : x=${origin.x}, y=${origin.y}, z=${origin.z}"
            )

            Log.d(
                "QRTracker2",
                "aaaaa Rotation origine caméra : ${origin.rotationMatrix.contentToString()}"
            )

        } catch (e: Exception) {
            Log.e("QRTracker2", "Erreur analyse caméra VR", e)

        } finally {
            cameraMatrix?.release()
            distortion?.release()

            gray.release()
            ids.release()
            corners.forEach { it.release() }
            rejected.forEach { it.release() }

            image.close()
            processing.set(false)
        }
    }

    private fun toWorldCoordinates(
        marker: MarkerPose,
        origin: MarkerPose
    ): WorldMarkerPose {

        val dx = marker.x - origin.x
        val dy = marker.y - origin.y
        val dz = marker.z - origin.z

        val r0 = origin.rotationMatrix
        val ri = marker.rotationMatrix

        // Position relative : R0 transposée × (ti - t0)
        val worldX = r0[0] * dx + r0[3] * dy + r0[6] * dz
        val worldY = r0[1] * dx + r0[4] * dy + r0[7] * dz
        val worldZ = r0[2] * dx + r0[5] * dy + r0[8] * dz

        // Rotation relative : R0 transposée × Ri
        val worldRotation = DoubleArray(9)

        for (row in 0 until 3) {
            for (col in 0 until 3) {
                worldRotation[row * 3 + col] =
                    r0[row] * ri[col] +
                            r0[3 + row] * ri[3 + col] +
                            r0[6 + row] * ri[6 + col]
            }
        }

        return WorldMarkerPose(
            x = worldX,
            y = worldY,
            z = worldZ,
            rotationMatrix = worldRotation
        )
    }

    override fun close() {
        poseEstimator.release()
    }
}
