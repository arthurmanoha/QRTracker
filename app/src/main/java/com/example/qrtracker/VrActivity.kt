
package com.example.qrtracker

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.AssetManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VrActivity : Activity(), GLSurfaceView.Renderer {

    private lateinit var glSurfaceView: GLSurfaceView
    private var nativeApp: Long = 0

    companion object {
        init {
            System.loadLibrary("cardboard_jni")
        }
    }

    private external fun nativeOnCreate(assets: AssetManager): Long
    private external fun nativeOnDestroy(app: Long)
    private external fun nativeOnSurfaceCreated(app: Long)
    private external fun nativeOnDrawFrame(app: Long)
    private external fun nativeOnTriggerEvent(app: Long)
    private external fun nativeOnPause(app: Long)
    private external fun nativeOnResume(app: Long)
    private external fun nativeSetScreenParams(app: Long, width: Int, height: Int)
    private external fun nativeSwitchViewer(app: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        // Initialisation du moteur Cardboard natif
        nativeApp = nativeOnCreate(assets)

        // Surface OpenGL
        glSurfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(this@VrActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP && nativeApp != 0L) {
                    queueEvent {
                        nativeOnTriggerEvent(nativeApp)
                    }
                }
                true
            }
        }

        setContentView(glSurfaceView)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        nativeOnSurfaceCreated(nativeApp)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        nativeSetScreenParams(nativeApp, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        nativeOnDrawFrame(nativeApp)
    }

    override fun onPause() {
        if (nativeApp != 0L) {
            nativeOnPause(nativeApp)
        }
        glSurfaceView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()

        if (nativeApp != 0L) {
            nativeOnResume(nativeApp)
        }
    }

    override fun onDestroy() {
        if (nativeApp != 0L) {
            nativeOnDestroy(nativeApp)
            nativeApp = 0
        }
        super.onDestroy()
    }
}
