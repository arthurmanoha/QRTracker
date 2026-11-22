package com.example.qrtracker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
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
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import android.content.Intent

data class OverlayMarker(
    val id: Int,
    val points: FloatArray
)

data class ReferenceFrame(
    val markerId: Int,
    val cameraPosition: DoubleArray,
    val cameraRotation: DoubleArray
)

data class WorldMarkerPose(
    val id: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val rotationMatrix: DoubleArray
)

private fun multiply3x3(
    a: DoubleArray,
    b: DoubleArray
): DoubleArray {
    val result = DoubleArray(9)

    for (row in 0..2) {
        for (col in 0..2) {
            for (k in 0..2) {
                result[row * 3 + col] +=
                    a[row * 3 + k] * b[k * 3 + col]
            }
        }
    }

    return result
}

private fun multiply3x3Vector(
    r: DoubleArray,
    v: DoubleArray
): DoubleArray {
    return doubleArrayOf(
        r[0] * v[0] + r[1] * v[1] + r[2] * v[2],
        r[3] * v[0] + r[4] * v[1] + r[5] * v[2],
        r[6] * v[0] + r[7] * v[1] + r[8] * v[2]
    )
}

private fun cameraPoseFromKnownMarker(
    knownMarker: WorldMarkerPose,
    observedPose: MarkerPose
): ReferenceFrame {

    // Transformation inverse : caméra -> marqueur
    val r = observedPose.rotationMatrix

    val rotationMC = doubleArrayOf(
        r[0], r[3], r[6],
        r[1], r[4], r[7],
        r[2], r[5], r[8]
    )

    val translationMC = doubleArrayOf(
        -(r[0] * observedPose.x +
                r[3] * observedPose.y +
                r[6] * observedPose.z),

        -(r[1] * observedPose.x +
                r[4] * observedPose.y +
                r[7] * observedPose.z),

        -(r[2] * observedPose.x +
                r[5] * observedPose.y +
                r[8] * observedPose.z)
    )

    // Transformation caméra -> monde
    val rotationWC = multiply3x3(
        knownMarker.rotationMatrix,
        rotationMC
    )

    val rotatedTranslation = multiply3x3Vector(
        knownMarker.rotationMatrix,
        translationMC
    )

    return ReferenceFrame(
        markerId = knownMarker.id,
        cameraPosition = doubleArrayOf(
            knownMarker.x + rotatedTranslation[0],
            knownMarker.y + rotatedTranslation[1],
            knownMarker.z + rotatedTranslation[2]
        ),
        cameraRotation = rotationWC
    )
}


private const val MAP_PREFERENCES = "qrtracker_map"
private const val MAP_KEY = "markers"
private const val REFERENCE_KEY = "reference_id"

private fun saveWorldMap(
    context: android.content.Context,
    map: Map<Int, WorldMarkerPose>
) {
    val array = JSONArray()

    map.values.forEach { marker ->
        val item = JSONObject()

        item.put("id", marker.id)
        item.put("x", marker.x)
        item.put("y", marker.y)
        item.put("z", marker.z)

        val rotation = JSONArray()
        marker.rotationMatrix.forEach { rotation.put(it) }

        item.put("rotation", rotation)
        array.put(item)
    }

    context.getSharedPreferences(
        MAP_PREFERENCES,
        android.content.Context.MODE_PRIVATE
    ).edit()
        .putString(MAP_KEY, array.toString())
        .apply()
}

private fun loadWorldMap(
    context: android.content.Context
): Map<Int, WorldMarkerPose> {

    val preferences = context.getSharedPreferences(
        MAP_PREFERENCES,
        android.content.Context.MODE_PRIVATE
    )

    val json = preferences.getString(MAP_KEY, null)
        ?: return emptyMap()

    return try {
        val array = JSONArray(json)
        val result = mutableMapOf<Int, WorldMarkerPose>()

        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val rotationJson = item.getJSONArray("rotation")

            if (rotationJson.length() != 9) continue

            val rotation = DoubleArray(9) { index ->
                rotationJson.getDouble(index)
            }

            val marker = WorldMarkerPose(
                id = item.getInt("id"),
                x = item.getDouble("x"),
                y = item.getDouble("y"),
                z = item.getDouble("z"),
                rotationMatrix = rotation
            )

            result[marker.id] = marker
        }

        result
    } catch (e: Exception) {
        e.printStackTrace()
        emptyMap()
    }
}



private fun transformMarkerToWorld(
    cameraInWorld: ReferenceFrame,
    pose: MarkerPose,
    id: Int
): WorldMarkerPose {

    // Orientation du marqueur dans le monde :
    // R_WM = R_WC * R_CM

    val worldRotation = multiply3x3(
        cameraInWorld.cameraRotation,
        pose.rotationMatrix
    )

    // Position du marqueur dans le monde :
    // t_WM = R_WC * t_CM + t_WC

    val relativePosition = multiply3x3Vector(
        cameraInWorld.cameraRotation,
        doubleArrayOf(pose.x, pose.y, pose.z)
    )

    val worldX =
        relativePosition[0] + cameraInWorld.cameraPosition[0]

    val worldY =
        relativePosition[1] + cameraInWorld.cameraPosition[1]

    val worldZ =
        relativePosition[2] + cameraInWorld.cameraPosition[2]

    return WorldMarkerPose(
        id = id,
        x = worldX,
        y = worldY,
        z = worldZ,
        rotationMatrix = worldRotation
    )
}

class MarkerOverlayView(context: android.content.Context) : View(context) {

    private var markers = emptyList<OverlayMarker>()

    var onMarkerTapped: ((Int) -> Unit)? = null

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

    override fun onTouchEvent(event: MotionEvent): Boolean {

        if (onMarkerTapped == null) return false

        if (event.action == MotionEvent.ACTION_UP) {

            for (marker in markers) {

                if (isPointInsidePolygon(
                        event.x,
                        event.y,
                        marker.points
                    )
                ) {
                    onMarkerTapped?.invoke(marker.id)
                    performClick()
                    return true
                }
            }
        }

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun isPointInsidePolygon(
        x: Float,
        y: Float,
        points: FloatArray
    ): Boolean {

        if (points.size != 8) return false

        var inside = false
        var j = 3

        for (i in 0..3) {

            val xi = points[i * 2]
            val yi = points[i * 2 + 1]

            val xj = points[j * 2]
            val yj = points[j * 2 + 1]

            if (
                (yi > y) != (yj > y) &&
                x < (xj - xi) * (y - yi) / (yj - yi) + xi
            ) {
                inside = !inside
            }

            j = i
        }

        return inside
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

    // Affichage de l'interface de calibration
    var showCalibration by remember {
        mutableStateOf(false)
    }

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

    // Dernières positions 3D calculées pour les marqueurs visibles
    val latestMarkerPoses = remember {
        AtomicReference<Map<Int, MarkerPose>>(emptyMap())
    }



    var referenceFrame by remember {
        mutableStateOf<ReferenceFrame?>(null)
    }

    // Copie accessible depuis le thread d'analyse caméra
    val referenceFrameAtomic = remember {
        AtomicReference<ReferenceFrame?>(null)
    }

    // Poses des marqueurs exprimées dans le repère du monde
    val initialWorldMap = remember {
        loadWorldMap(context)
    }

    var worldMarkerPoses by remember {
        mutableStateOf(initialWorldMap)
    }

    val worldMarkerPosesAtomic = remember {
        AtomicReference<Map<Int, WorldMarkerPose>>(initialWorldMap)
    }

    // Identifiant du marqueur choisi comme référence
    var selectedReferenceId by remember {
        mutableStateOf(
            context.getSharedPreferences(
                MAP_PREFERENCES,
                android.content.Context.MODE_PRIVATE
            ).getInt(REFERENCE_KEY, -1).takeIf { it >= 0 }
        )
    }

    var referenceMessage by remember {
        mutableStateOf("Aucun marqueur de référence sélectionné")
    }

    LaunchedEffect(worldMarkerPoses) {
        saveWorldMap(context, worldMarkerPoses)
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

    // La permission caméra n'est demandée qu'à l'ouverture
    // de l'interface de calibration.
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
        AndroidView(
            factory = { ctx ->

                val container = FrameLayout(ctx)

                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }

                val overlayView = MarkerOverlayView(ctx)

                overlayView.onMarkerTapped = { id ->

                    if (!calibrationReady.get()) {

                        referenceMessage = "La calibration est nécessaire."

                    } else {

                        val pose = latestMarkerPoses.get()[id]

                        if (pose == null) {

                            referenceMessage =
                                "Position 3D indisponible pour le marqueur ID $id. Réessaie."

                        } else {

                            val r = pose.rotationMatrix

                            // Inversion de la transformation marker -> camera.
                            // On obtient la position de la caméra dans le repère du marqueur.

                            val cameraX = -(
                                    r[0] * pose.x +
                                            r[3] * pose.y +
                                            r[6] * pose.z
                                    )

                            val cameraY = -(
                                    r[1] * pose.x +
                                            r[4] * pose.y +
                                            r[7] * pose.z
                                    )

                            val cameraZ = -(
                                    r[2] * pose.x +
                                            r[5] * pose.y +
                                            r[8] * pose.z
                                    )

                            // La rotation inverse est la transposée de R.
                            val cameraRotation = doubleArrayOf(
                                r[0], r[3], r[6],
                                r[1], r[4], r[7],
                                r[2], r[5], r[8]
                            )

                            val newReferenceFrame = ReferenceFrame(
                                markerId = id,
                                cameraPosition = doubleArrayOf(
                                    cameraX,
                                    cameraY,
                                    cameraZ
                                ),
                                cameraRotation = cameraRotation
                            )

                            referenceFrame = newReferenceFrame
                            // Le marqueur sélectionné devient l'origine du monde.
                            val identityRotation = doubleArrayOf(
                                1.0, 0.0, 0.0,
                                0.0, 1.0, 0.0,
                                0.0, 0.0, 1.0
                            )

                            val referenceMarker = WorldMarkerPose(
                                id = id,
                                x = 0.0,
                                y = 0.0,
                                z = 0.0,
                                rotationMatrix = identityRotation
                            )

                            // Nouvelle carte : on repart du marqueur de référence.
                            val newMap = mapOf(id to referenceMarker)

                            worldMarkerPosesAtomic.set(newMap)
                            worldMarkerPoses = newMap

                            selectedReferenceId = id

                            context.getSharedPreferences(
                                MAP_PREFERENCES,
                                android.content.Context.MODE_PRIVATE
                            ).edit()
                                .putInt(REFERENCE_KEY, id)
                                .apply()

                            referenceMessage = "Référence définie : AprilTag ID $id"

                            // Rend immédiatement la nouvelle référence accessible
                            // à l'analyseur de caméra.
                            referenceFrameAtomic.set(newReferenceFrame)

                            // Les anciennes positions ne correspondent plus
                            // nécessairement au nouveau repère.
                            worldMarkerPoses = emptyMap()

                            selectedReferenceId = id

                            referenceMessage = "Référence définie : AprilTag ID $id"
                        }
                    }
                }

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

// Dimensions de l'image caméra
                            calibrationImageWidth.set(width)
                            calibrationImageHeight.set(height)

// Calibration approximative automatique au premier démarrage
                            if (!calibrationReady.get() && width > 0 && height > 0) {
                                try {
                                    synchronized(cameraCalibration) {
                                        if (!calibrationReady.get()) {

                                            cameraCalibration.useApproximateCalibration(
                                                Size(width.toDouble(), height.toDouble())
                                            )

                                            // Publication de la calibration pour VrActivity
                                            CameraCalibrationState.cameraMatrix =
                                                cameraCalibration.cameraMatrix.clone()

                                            CameraCalibrationState.distortionCoefficients =
                                                org.opencv.core.MatOfDouble().apply {
                                                    fromArray(
                                                        *cameraCalibration.distortionCoefficients.toArray()
                                                    )
                                                }

                                            CameraCalibrationState.imageWidth = width
                                            CameraCalibrationState.imageHeight = height

                                            CameraCalibrationState.ready.set(true)
                                            calibrationReady.set(true)
                                        }
                                    }

                                    ContextCompat.getMainExecutor(ctx).execute {
                                        calibrationMessage =
                                            "Calibration approximative automatique activée."
                                        markerPoseText = "Calibration approximative active"
                                    }

                                    android.util.Log.i(
                                        "QRTracker",
                                        "Calibration approximative automatique activée"
                                    )

                                } catch (e: Exception) {
                                    android.util.Log.e(
                                        "QRTracker",
                                        "Erreur de calibration automatique",
                                        e
                                    )
                                }
                            }


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
                            val currentDetector = detector

                            if (currentDetector != null) {
                                currentDetector.detectMarkers(
                                    gray,
                                    corners,
                                    ids,
                                    rejected
                                )
                            }

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

                            latestMarkerPoses.set(
                                foundPoses.associate { (id, pose) -> id to pose }
                            )

                            // Reconstruction et actualisation de la carte 3D
                            val currentMap = worldMarkerPosesAtomic.get()

                            if (currentMap.isNotEmpty() && foundPoses.isNotEmpty()) {

                                // Cherche un marqueur déjà connu et actuellement visible.
                                // Il sert à retrouver la position actuelle de la caméra.
                                val cameraPose = foundPoses.firstNotNullOfOrNull { (id, pose) ->

                                    val knownMarker = currentMap[id]

                                    if (knownMarker != null) {
                                        cameraPoseFromKnownMarker(knownMarker, pose)
                                    } else {
                                        null
                                    }
                                }

                                if (cameraPose != null) {

                                    // Calcule les positions de tous les marqueurs visibles
                                    // dans le repère du monde.
                                    val updatedMarkers = foundPoses.associate { (id, pose) ->

                                        id to transformMarkerToWorld(
                                            cameraPose,
                                            pose,
                                            id
                                        )
                                    }

                                    // Ajoute les nouveaux marqueurs et actualise les anciens.
                                    val updatedMap = currentMap + updatedMarkers

                                    worldMarkerPosesAtomic.set(updatedMap)

                                    ContextCompat.getMainExecutor(ctx).execute {
                                        worldMarkerPoses = updatedMap
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
                                        foundPoses.joinToString("") { (id, pose) ->
                                                ""
//                                        foundPoses.joinToString("\n\n") { (id, pose) ->
//                                            String.format(
//                                                java.util.Locale.FRANCE,
//                                                "ID %d\nX : %.3f m\nY : %.3f m\nZ : %.3f m\nDistance : %.3f m\nRoll : %.1f°\nPitch : %.1f°\nYaw : %.1f°",
//                                                id,
//                                                pose.x,
//                                                pose.y,
//                                                pose.z,
//                                                pose.distance,
//                                                pose.roll,
//                                                pose.pitch,
//                                                pose.yaw
//                                            )
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

        if (showCalibration) {

            // Gestion du bouton Retour physique Android
            BackHandler {
                showCalibration = false
            }

            // Bouton de retour à l'écran principal
            Button(
                onClick = {
                    showCalibration = false
                }
            ) {
                Text("Retour")
            }

            Spacer(modifier = Modifier.height(8.dp))

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

                                    CameraCalibrationState.cameraMatrix =
                                        cameraCalibration.cameraMatrix.clone()

                                    CameraCalibrationState.distortionCoefficients =
                                        org.opencv.core.MatOfDouble().apply {
                                            fromArray(
                                                *cameraCalibration.distortionCoefficients.toArray()
                                            )
                                        }

                                    CameraCalibrationState.imageWidth = calibrationImageWidth.get()
                                    CameraCalibrationState.imageHeight = calibrationImageHeight.get()
                                }

                                CameraCalibrationState.ready.set(true)
                                calibrationMessage = "Calibration réussie..."
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

                                        CameraCalibrationState.cameraMatrix =
                                            cameraCalibration.cameraMatrix.clone()

                                        CameraCalibrationState.distortionCoefficients =
                                            org.opencv.core.MatOfDouble().apply {
                                                fromArray(
                                                    *cameraCalibration.distortionCoefficients.toArray()
                                                )
                                            }

                                        CameraCalibrationState.imageWidth = width
                                        CameraCalibrationState.imageHeight = height
                                    }

                                    CameraCalibrationState.ready.set(true)
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


                }
            }

        } else {

            // Écran principal : aucune interface de calibration visible
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        showCalibration = true
                    }
                ) {
                    Text("Calibration")
                }

                Button(
                    modifier = Modifier.weight(1f),
                    enabled = CameraCalibrationState.ready.get(),
                    onClick = {
                        context.startActivity(
                            Intent(context, VrActivity::class.java)
                        )
                    }
                ) {
                    Text("VR")
                }
            }

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
            Spacer(modifier = Modifier.height(12.dp))

            if (calibrationReady.get()) {

                Text(
                    text = "Touche le contour d'un AprilTag pour le choisir comme référence.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Text(
                text = referenceMessage,
                style = MaterialTheme.typography.titleMedium
            )

            if (referenceFrame != null) {

                val frame = referenceFrame!!

                Text(
                    text = String.format(
                        java.util.Locale.FRANCE,
                        "Caméra dans le repère ID %d\nX : %.3f m\nY : %.3f m\nZ : %.3f m",
                        frame.markerId,
                        frame.cameraPosition[0],
                        frame.cameraPosition[1],
                        frame.cameraPosition[2]
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Marqueurs dans le repère de référence :",
                style = MaterialTheme.typography.titleMedium
            )

            worldMarkerPoses.values
                .sortedBy { it.id }
                .forEach { pose ->

                    Text(
                        text = String.format(
                            java.util.Locale.FRANCE,
                            "ID %d : X = %.3f m | Y = %.3f m | Z = %.3f m",
                            pose.id,
                            pose.x,
                            pose.y,
                            pose.z
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            Text(
                text = "Carte 3D : ${worldMarkerPoses.size} marqueur(s) enregistré(s)",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}