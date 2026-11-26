
package com.example.qrtracker

import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.AssetManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import org.opencv.android.OpenCVLoader
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.graphics.Rect
import android.os.Build

class VrActivity : ComponentActivity(), GLSurfaceView.Renderer {

    private lateinit var glSurfaceView: GLSurfaceView
    private var nativeApp: Long = 0

    // CameraX
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraAnalyzer: VrCameraAnalyzer? = null
    private lateinit var cameraExecutor: ExecutorService

    companion object {
        init {
            System.loadLibrary("cardboard_jni")
        }

        private const val TAG = "QRTracker2"
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startCameraAnalysis()
            } else {
                Log.e(TAG, "Permission caméra refusée")
            }
        }

    // Fonctions natives Cardboard
    private external fun nativeOnCreate(assets: AssetManager): Long
    private external fun nativeOnDestroy(app: Long)
    private external fun nativeOnSurfaceCreated(app: Long)
    private external fun nativeOnDrawFrame(app: Long)
    private external fun nativeOnTriggerEvent(app: Long)
    private external fun nativeOnPause(app: Long)
    private external fun nativeOnResume(app: Long)

    private external fun nativeSetScreenParams(
        app: Long,
        width: Int,
        height: Int
    )

    private external fun nativeSetMarkers(
        app: Long,
        imageTimestamp: Long,
        ids: IntArray,
        positions: FloatArray,
        rotations: FloatArray
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logCameraCharacteristics()

        if (!OpenCVLoader.initLocal()) {
            throw RuntimeException("Impossible de charger OpenCV")
        }

        Log.d(TAG, "OpenCV initialisé")

        cameraExecutor = Executors.newSingleThreadExecutor()

        requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        nativeApp = nativeOnCreate(assets)

        glSurfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(this@VrActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP &&
                    nativeApp != 0L
                ) {
                    queueEvent {
                        nativeOnTriggerEvent(nativeApp)
                    }
                }
                true
            }
        }

        setContentView(glSurfaceView)
    }

    // --------------------------------------------------
    // CAMERA
    // --------------------------------------------------

    private fun startCameraAnalysis() {

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(
                Manifest.permission.CAMERA
            )
            return
        }

        val future = ProcessCameraProvider.getInstance(this)

        future.addListener({

            try {
                val provider = future.get()
                cameraProvider = provider

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                    )
                    .build()

                cameraAnalyzer = VrCameraAnalyzer { markers, imageTimestamp ->

                    Log.d(TAG, "Marqueurs mondiaux reçus : ${markers.keys}")

                    if (nativeApp != 0L) {

                        val sortedMarkers = markers.toSortedMap()
                        val ids = sortedMarkers.keys.toIntArray()

                        val positions = FloatArray(ids.size * 3)
                        val rotations = FloatArray(ids.size * 9)

                        sortedMarkers.values.forEachIndexed { index, marker ->

                            positions[index * 3] = marker.x.toFloat()
                            positions[index * 3 + 1] = marker.y.toFloat()
                            positions[index * 3 + 2] = marker.z.toFloat()

                            for (i in 0 until 9) {
                                rotations[index * 9 + i] =
                                    marker.rotationMatrix[i].toFloat()
                            }
                        }

                        glSurfaceView.queueEvent {
                            if (nativeApp != 0L) {
                                nativeSetMarkers(
                                    nativeApp,
                                    imageTimestamp,
                                    ids,
                                    positions,
                                    rotations
                                )
                            }
                        }
                    }
                }

                analysis.setAnalyzer(
                    cameraExecutor,
                    cameraAnalyzer!!
                )

                provider.unbindAll()

                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    analysis
                )

                Log.d(TAG, "Analyse caméra démarrée")

            } catch (e: Exception) {
                Log.e(TAG, "Erreur démarrage caméra", e)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCameraAnalysis() {
        cameraProvider?.unbindAll()
        cameraProvider = null

        cameraAnalyzer?.close()
        cameraAnalyzer = null

        Log.d(TAG, "Analyse caméra arrêtée")
    }

    // --------------------------------------------------
    // RENDU VR
    // --------------------------------------------------

    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?
    ) {
        nativeOnSurfaceCreated(nativeApp)
    }

    override fun onSurfaceChanged(
        gl: GL10?,
        width: Int,
        height: Int
    ) {
        nativeSetScreenParams(nativeApp, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (nativeApp != 0L) {
            nativeOnDrawFrame(nativeApp)
        }
    }

    // --------------------------------------------------
    // CYCLE DE VIE
    // --------------------------------------------------

    override fun onResume() {
        super.onResume()

        glSurfaceView.onResume()

        if (nativeApp != 0L) {
            nativeOnResume(nativeApp)
        }

        startCameraAnalysis()
    }

    override fun onPause() {
        stopCameraAnalysis()

        if (nativeApp != 0L) {
            nativeOnPause(nativeApp)
        }

        glSurfaceView.onPause()

        super.onPause()
    }

    override fun onDestroy() {
        stopCameraAnalysis()

        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }

        if (nativeApp != 0L) {
            nativeOnDestroy(nativeApp)
            nativeApp = 0
        }

        super.onDestroy()
    }
    private fun logCameraCharacteristics() {

        val manager = getSystemService(CAMERA_SERVICE) as CameraManager

        for (cameraId in manager.cameraIdList) {

            val characteristics =
                manager.getCameraCharacteristics(cameraId)

            val facing = characteristics.get(
                CameraCharacteristics.LENS_FACING
            )

            if (facing != CameraCharacteristics.LENS_FACING_BACK) {
                continue
            }

            val focalLengths = characteristics.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            )

            val sensorSize = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
            )

            val pixelArray = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE
            )

            val activeArray = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
            )

            val intrinsics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                characteristics.get(
                    CameraCharacteristics.LENS_INTRINSIC_CALIBRATION
                )
            } else {
                null
            }

            val distortion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                characteristics.get(
                    CameraCharacteristics.LENS_DISTORTION
                )
            } else {
                null
            }

            Log.d("QRTracker2", "CAMERA ID = $cameraId")
            Log.d("QRTracker2", "Focal lengths = ${focalLengths?.contentToString()}")
            Log.d("QRTracker2", "Sensor size = $sensorSize")
            Log.d("QRTracker2", "Pixel array = $pixelArray")
            Log.d("QRTracker2", "Active array = $activeArray")
            Log.d("QRTracker2", "Intrinsics = ${intrinsics?.contentToString()}")
            Log.d("QRTracker2", "Distortion = ${distortion?.contentToString()}")
            val lensRotation =
                characteristics.get(CameraCharacteristics.LENS_POSE_ROTATION)
            val lensTranslation =
                characteristics.get(CameraCharacteristics.LENS_POSE_TRANSLATION)
            Log.d("QRTracker2", "LENS_POSE_ROTATION = ${lensRotation?.contentToString()}")
            Log.d("QRTracker2", "LENS_POSE_TRANSLATION = ${lensTranslation?.contentToString()}")
        }
    }
}
