package com.example.qrtracker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.qrtracker.ui.theme.QRTrackerTheme
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect
import java.util.concurrent.Executors
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.ImageProxy
import org.opencv.calib3d.Calib3d
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class OverlayMarker(
    val id: Int,
    val points: FloatArray
)

class MarkerOverlayView(context: android.content.Context) : View(context) {

    private var markers = emptyList<OverlayMarker>()

    private val linePaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.GREEN
        textSize = 38f
        style = Paint.Style.FILL
        isAntiAlias = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    fun setMarkers(newMarkers: List<OverlayMarker>) {
        markers = newMarkers
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (marker in markers) {
            val p = marker.points

            if (p.size != 8) continue

            val path = Path().apply {
                moveTo(p[0], p[1])
                lineTo(p[2], p[3])
                lineTo(p[4], p[5])
                lineTo(p[6], p[7])
                close()
            }

            canvas.drawPath(path, linePaint)

            canvas.drawText(
                "ID ${marker.id}",
                p[0],
                p[1] - 12f,
                textPaint
            )
        }
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val openCvReady = OpenCVLoader.initLocal()

        @androidx.camera.view.TransformExperimental
        setContent {
            QRTrackerTheme {
                CameraScreen(openCvReady)
            }
        }
    }
}

@androidx.camera.view.TransformExperimental
@Composable
fun CameraScreen(openCvReady: Boolean) {

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var detectedIds by remember {
        mutableStateOf(emptyList<Int>())
    }

    // Résultats de l'estimation de pose
    var markerPoseText by remember {
        mutableStateOf("Calibration nécessaire")
    }

    // Indique si la calibration a été calculée
    val calibrationReady = remember {
        AtomicBoolean(false)
    }

    // Estimateur de position 3D
    val markerPoseEstimator = remember {
        MarkerPoseEstimator()
    }

    // Calibration de la caméra
    val cameraCalibration = remember {
        CameraCalibration()
    }

    val captureCalibrationFrame = remember {
        AtomicBoolean(false)
    }

    val calibrationImageWidth = remember {
        AtomicInteger(0)
    }

    val calibrationImageHeight = remember {
        AtomicInteger(0)
    }

    var calibrationCount by remember {
        mutableStateOf(0)
    }

    var calibrationMessage by remember {
        mutableStateOf("Calibration non commencée")
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val detector = remember(openCvReady) {
        if (openCvReady) {
            val dictionary = Objdetect.getPredefinedDictionary(
                Objdetect.DICT_APRILTAG_36h11
            )

            ArucoDetector(
                dictionary,
                DetectorParameters()
            )
        } else {
            null
        }
    }

    val analysisExecutor = remember {
        Executors.newSingleThreadExecutor()
    }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Text(
            text = "QRTracker",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(12.dp))

        when {
            !openCvReady -> {
                Text("Erreur : impossible de charger OpenCV")
            }

            detector == null -> {
                Text("Détecteur indisponible")
            }

            !hasCameraPermission -> {
                Button(
                    onClick = {
                        permissionLauncher.launch(
                            Manifest.permission.CAMERA
                        )
                    }
                ) {
                    Text("Autoriser la caméra")
                }
            }

            else -> {

                AndroidView(
                    factory = { ctx ->

                        val container = FrameLayout(ctx)

                        val previewView = PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }

                        val overlayView = MarkerOverlayView(ctx)

                        container.addView(
                            previewView,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        )

                        container.addView(
                            overlayView,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        )

                        val cameraProviderFuture =
                            ProcessCameraProvider.getInstance(ctx)

                        cameraProviderFuture.addListener({

                            val cameraProvider =
                                cameraProviderFuture.get()

                            val preview = Preview.Builder()
                                .build()
                                .also {
                                    it.surfaceProvider =
                                        previewView.surfaceProvider
                                }

                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(
                                    ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                                )
                                .build()

                            // Les coordonnées OpenCV correspondent
                            // à l'image originale non tournée.
                            val transformFactory =
                                ImageProxyTransformFactory()

                            analysis.setAnalyzer(analysisExecutor) { image ->

                                val gray = Mat()
                                val ids = Mat()
                                val corners = ArrayList<Mat>()
                                val rejected = ArrayList<Mat>()

                                try {
                                    val plane = image.planes[0]
                                    val buffer = plane.buffer

                                    val width = image.width
                                    val height = image.height

                                    val rowStride = plane.rowStride
                                    val pixelStride = plane.pixelStride
                                    val base = buffer.position()

                                    val grayBytes =
                                        ByteArray(width * height)

                                    for (y in 0 until height) {
                                        for (x in 0 until width) {
                                            val index =
                                                base +
                                                        y * rowStride +
                                                        x * pixelStride

                                            grayBytes[y * width + x] =
                                                buffer.get(index)
                                        }
                                    }

                                    gray.create(
                                        height,
                                        width,
                                        CvType.CV_8UC1
                                    )

                                    gray.put(0, 0, grayBytes)

                                    // Capture d'une position de la mire sur demande
                                    calibrationImageWidth.set(width)
                                    calibrationImageHeight.set(height)

                                    if (captureCalibrationFrame.getAndSet(false)) {

                                        val added = synchronized(cameraCalibration) {
                                            cameraCalibration.addImage(gray)
                                        }

                                        ContextCompat.getMainExecutor(ctx).execute {

                                            if (added) {
                                                calibrationCount++

                                                calibrationMessage =
                                                    "Position enregistrée : $calibrationCount"
                                            } else {
                                                calibrationMessage =
                                                    "Mire non détectée. Réessaie."
                                            }
                                        }
                                    }

                                    detector.detectMarkers(
                                        gray,
                                        corners,
                                        ids,
                                        rejected
                                    )

                                    val foundMarkers =
                                        ArrayList<Pair<Int, FloatArray>>()

                                    val foundIds =
                                        ArrayList<Int>()

                                    val foundPoses =
                                        ArrayList<Pair<Int, MarkerPose>>()

                                    for (i in 0 until ids.rows()) {

                                        val id = ids.get(i, 0)[0].toInt()
                                        foundIds.add(id)

                                        val corner = corners[i]
                                        val points = FloatArray(8)

                                        for (j in 0..3) {
                                            val xy = corner.get(0, j)

                                            points[j * 2] =
                                                xy[0].toFloat()

                                            points[j * 2 + 1] =
                                                xy[1].toFloat()
                                        }

                                        foundMarkers.add(id to points)

                                        // Calcul de la position 3D si la caméra est calibrée
                                        if (calibrationReady.get()) {

                                            val pose = synchronized(cameraCalibration) {
                                                markerPoseEstimator.estimate(
                                                    corner,
                                                    cameraCalibration.cameraMatrix,
                                                    cameraCalibration.distortionCoefficients
                                                )
                                            }

                                            if (pose != null) {
                                                foundPoses.add(id to pose)
                                            }
                                        }
                                    }

                                    // Transformation des coordonnées
                                    // de l'image vers PreviewView.
                                    val sourceTransform =
                                        transformFactory.getOutputTransform(image)

                                    overlayView.post {

                                        val targetTransform =
                                            previewView.outputTransform

                                        if (targetTransform != null) {

                                            val transform =
                                                CoordinateTransform(
                                                    sourceTransform,
                                                    targetTransform
                                                )

                                            val displayMarkers =
                                                foundMarkers.map { (id, rawPoints) ->

                                                    val mappedPoints =
                                                        rawPoints.copyOf()

                                                    transform.mapPoints(
                                                        mappedPoints
                                                    )

                                                    OverlayMarker(
                                                        id,
                                                        mappedPoints
                                                    )
                                                }

                                            overlayView.setMarkers(
                                                displayMarkers
                                            )
                                        }
                                    }

                                    ContextCompat.getMainExecutor(ctx).execute {

                                        detectedIds = foundIds

                                        markerPoseText = when {

                                            !calibrationReady.get() -> {
                                                "Calibration nécessaire"
                                            }

                                            foundPoses.isEmpty() -> {
                                                "Aucune position calculée"
                                            }

                                            else -> {
                                                foundPoses.joinToString("\n\n") { (id, pose) ->

                                                    String.format(
                                                        java.util.Locale.FRANCE,
                                                        "ID %d\nX : %.3f m\nY : %.3f m\nZ : %.3f m\nDistance : %.3f m\nRoll : %.1f°\nPitch : %.1f°\nYaw : %.1f°",
                                                        id,
                                                        pose.x,
                                                        pose.y,
                                                        pose.z,
                                                        pose.distance,
                                                        pose.roll,
                                                        pose.pitch,
                                                        pose.yaw
                                                    )
                                                }
                                            }
                                        }
                                    }

                                } catch (e: Exception) {
                                    e.printStackTrace()
                                } finally {
                                    gray.release()
                                    ids.release()

                                    corners.forEach { it.release() }
                                    rejected.forEach { it.release() }

                                    image.close()
                                }
                            }

                            // On attend que PreviewView ait une taille
                            // avant de récupérer son ViewPort.
                            previewView.post {

                                try {
                                    val viewPort = previewView.viewPort

                                    if (viewPort == null) {
                                        android.util.Log.e(
                                            "QRTracker",
                                            "PreviewView ViewPort est null"
                                        )
                                        return@post
                                    }

                                    val useCaseGroup = UseCaseGroup.Builder()
                                        .setViewPort(viewPort)
                                        .addUseCase(preview)
                                        .addUseCase(analysis)
                                        .build()

                                    cameraProvider.unbindAll()

                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        useCaseGroup
                                    )

                                    android.util.Log.d(
                                        "QRTracker",
                                        "Caméra démarrée correctement"
                                    )

                                } catch (e: Exception) {
                                    android.util.Log.e(
                                        "QRTracker",
                                        "Erreur démarrage caméra",
                                        e
                                    )
                                }
                            }
                        }, ContextCompat.getMainExecutor(ctx))

                        container
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Positions de calibration : $calibrationCount / 15"
                )

                Text(
                    text = calibrationMessage
                )

                Button(
                    onClick = {
                        captureCalibrationFrame.set(true)
                        calibrationMessage = "Capture en attente..."
                    }
                ) {
                    Text("Capturer cette position")
                }

                Button(
                    enabled = calibrationCount >= 15,
                    onClick = {

                        try {
                            val imageSize = Size(
                                calibrationImageWidth.get().toDouble(),
                                calibrationImageHeight.get().toDouble()
                            )

                            val rms = synchronized(cameraCalibration) {
                                cameraCalibration.calibrate(imageSize)
                            }

                            calibrationMessage =
                                "Calibration réussie. Erreur : $rms pixels"
                            calibrationReady.set(true)

                            android.util.Log.i(
                                "QRTracker",
                                "Camera Matrix : ${cameraCalibration.cameraMatrix.dump()}"
                            )

                            android.util.Log.i(
                                "QRTracker",
                                "Distortion : ${cameraCalibration.distortionCoefficients.dump()}"
                            )

                        } catch (e: Exception) {

                            calibrationMessage =
                                "Erreur de calibration : ${e.message}"
                            calibrationReady.set(false)

                            e.printStackTrace()
                        }
                    }
                ) {
                    Text("Calculer la calibration")
                }

                Button(
                    onClick = {

                        val width = calibrationImageWidth.get()
                        val height = calibrationImageHeight.get()

                        if (width <= 0 || height <= 0) {

                            calibrationMessage =
                                "En attente de démarrage de la caméra."

                        } else {

                            try {
                                synchronized(cameraCalibration) {
                                    cameraCalibration.useApproximateCalibration(
                                        Size(width.toDouble(), height.toDouble())
                                    )
                                }

                                calibrationReady.set(true)

                                calibrationMessage =
                                    "Calibration approximative activée."

                            } catch (e: Exception) {

                                calibrationReady.set(false)

                                calibrationMessage =
                                    "Erreur : ${e.message}"

                                e.printStackTrace()
                            }
                        }
                    }
                ) {
                    Text("Utiliser une calibration approximative")
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = if (detectedIds.isEmpty()) {
                        "Aucun AprilTag détecté"
                    } else {
                        "AprilTag détectés : " +
                                detectedIds.joinToString(", ")
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = markerPoseText,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
