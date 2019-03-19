// Copyright 2018 Ricoh Company, Ltd. All rights reserved.
// Copyright 2018 Shiguredo, Inc. All rights reserved.

package com.theta360.sample.livestreaming

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.camera2.*
import android.media.AudioManager
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import jp.shiguredo.sora.sdk.util.SoraLogger
import org.webrtc.Logging
import java.lang.RuntimeException


@Suppress("DEPRECATION")
class Camera2ToTextureViewActivity : Activity() {
    companion object {
        private val TAG = Camera2ToTextureViewActivity::class.simpleName
    }

    // Capture parameters
    // private val shootingMode = ThetaCapturer.ShootingMode.RIC_MOVIE_PREVIEW_3840
    private val shootingMode = ThetaCapturer.ShootingMode.RIC_MOVIE_RECORDING_4K_EQUI
    // private val shootingMode = ThetaCapturer.ShootingMode.RIC_MOVIE_RECORDING_4K_DUAL
    // private val shootingMode = ThetaCapturer.ShootingMode.RIC_MOVIE_RECORDING_2K_EQUI
    // private val shootingMode = ThetaCapturer.ShootingMode.RIC_MOVIE_RECORDING_2K_DUAL
    // private val shootingMode = ThetaCapturer.ShootingMode.RIC_STILL_PREVIEW_1920
    private val frameRate = 30

    private val mainHandler: Handler = Handler()

    private var cameraThread: HandlerThread? = null
    private val cameraThreadHandler: Handler? = null

    private var cameraManager: CameraManager? = null
    private var cameraId: String? = null
    private var camera: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var captureRequest: CaptureRequest? = null

    private var textureView: TextureView? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        SoraLogger.enabled = true
        SoraLogger.libjingle_enabled = true

        setContentView(R.layout.activity_texture_view)
        textureView = findViewById(R.id.textureView)
   }

    override fun onResume() {
        super.onResume()

        setupThetaDevices()


        textureView!!.surfaceTextureListener = object: TextureView.SurfaceTextureListener {
            var lastFrameCaptured = System.currentTimeMillis()
            val fpsIntervalFramesTarget = 30
            var fpsIntervalStart = lastFrameCaptured
            var fpsIntervalFrames = 0

            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                Logging.d(TAG, "onSurfaceTextureAvailable: ${st} ${width}x${height} on ${Thread.currentThread()}")
                surfaceTexture = st
                surface = Surface(st)

                val matrix = Matrix()
                // textureView!!.setTransform(matrix)
                // st.setDefaultBufferSize(shootingMode.width, shootingMode.height)
                st.setDefaultBufferSize(720, 360)

                // If setOnFrameAvailableListner is called here, no update on view.
                // It seems that there is a listener to propagate SurfaceTexture to view underneath.
/*
                st.setOnFrameAvailableListener( { _ ->
                    Logging.d(TAG, "onFrameAvailable: ${st} on ${Thread.currentThread()}")
                    // st.updateTexImage()

                    val current = System.currentTimeMillis()
                    Logging.d(TAG, "onFrameAvailable. interval=${current - lastFrameCaptured} msec")
                    lastFrameCaptured = current
                    if (fpsIntervalFrames != fpsIntervalFramesTarget) {
                        fpsIntervalFrames++
                    } else {
                        Logging.d(TAG, "SurfaceTexture: %.1f FPS".format(1000.0*fpsIntervalFramesTarget/(current - fpsIntervalStart)))
                        fpsIntervalFrames = 0
                        fpsIntervalStart = current
                    }
                    Unit

                }, mainHandler)
*/

                startCamera()
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                Logging.d(TAG, "onSurfaceTextureSizeChanged: ${surfaceTexture} ${width}x${height}")
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                Logging.d(TAG, "onSurfaceTextureUpdated")
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                Logging.d(TAG, "onSurfaceTextureDestroyed")
                return true
            }

        }
     }

    override fun onPause() {
        // Configures RICOH THETA's camera. This is not a general Android configuration.
        // see https://api.ricoh/docs/theta-plugin-reference/broadcast-intent/#notifying-camera-device-control
        SoraLogger.d(TAG, "Broadcast ACTION_MAIN_CAMERA_OPEN")
        ThetaCapturer.actionMainCameraOpen(applicationContext)

        cameraThread?.quit()

        super.onPause()
    }

    private fun setupThetaDevices() {
        // Configures RICOH THETA's microphone and camera. This is not a general Android configuration.
        // see https://api.ricoh/docs/theta-plugin-reference/audio-manager-api/
        // see https://api.ricoh/docs/theta-plugin-reference/broadcast-intent/#notifying-camera-device-control

        // recording in monaural
        (getSystemService(AUDIO_SERVICE) as AudioManager)
                .setParameters("RicUseBFormat=false")
        // Prepare to use camera
        SoraLogger.d(TAG, "Broadcast ACTION_MAIN_CAMERA_CLOSE")
        ThetaCapturer.actionMainCameraClose(applicationContext)
    }

    private val cameraStateCallback = object: CameraDevice.StateCallback() {
        override fun onOpened(cameraDevice: CameraDevice) {
            Logging.d(TAG, "Camera onOpened: $cameraDevice")

            // setCamera1Parameters()

            camera = cameraDevice
            Logging.d(TAG, "surface to camera: ${surface}")
            val outputs = listOf(surface)
            camera!!.createCaptureSession(outputs, captureSessionCallback,
                    mainHandler)
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            Logging.d(TAG, "Camera onDisconnected: $cameraDevice")
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            Logging.d(TAG, "Camera onError: $cameraDevice, error=$error")
        }
    }

    private val captureSessionCallback = object: CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            Logging.d(TAG, "CameraCaptureSession.onConfigured: $session")
            cameraSession = session

//            val cameraCharacteristics = cameraManager!!.getCameraCharacteristics(cameraId!!)
//            for (key in cameraCharacteristics.keys) {
//                Logging.d(TAG, "camera characteristics: key=[${key.name}] value=[${cameraCharacteristics.get(key)}]")
//            }
//            val availableKeys = cameraCharacteristics.availableCaptureRequestKeys
//            for (availableKey in availableKeys) {
//                Logging.d(TAG, "camera characteristic available: key=[$availableKey]}]")
//            }
            val captureRequestBuilder = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(surface)
            // val shootingModeKey = CaptureRequest.Key("RIC_SHOOTING_MODE", String.javaClass)
            // captureRequestBuilder.set(CaptureRequest.Key("RIC_SHOOTING_MODE", String), "RicMovieRecording4kEqui")

            captureRequest = captureRequestBuilder.build()
            session.setRepeatingRequest(captureRequest, captureCallback, mainHandler)
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            Logging.e(TAG, "CameraCaptureSession.onConfigureFailed: $session")
            throw RuntimeException("CameraCaptureSession.onConfigureFailed")
        }
    }

    private val captureCallback = object: CameraCaptureSession.CaptureCallback() {
        var lastCapturedMillis = System.currentTimeMillis()
        val fpsIntervalFramesTarget = 30
        var fpsIntervalStartMills = lastCapturedMillis
        var fpsIntervalFrames = 0

        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            super.onCaptureStarted(session, request, timestamp, frameNumber)
            val currentMillis = System.currentTimeMillis()
            // Logging.d(TAG, "CaptureCallback.onCaptureStarted: interval=${currentMillis - lastCapturedMillis} [msec]")

            lastCapturedMillis = currentMillis
            if (fpsIntervalFrames != fpsIntervalFramesTarget) {
                fpsIntervalFrames++
            } else {
                Logging.d(TAG, "Camera capture: %.1f FPS".format(1000.0*fpsIntervalFramesTarget/(currentMillis - fpsIntervalStartMills)))
                fpsIntervalFrames = 0
                fpsIntervalStartMills = currentMillis
            }

        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            super.onCaptureFailed(session, request, failure)
            Logging.d(TAG, "CaptureCallback.onCaptureFailed: ${failure}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCamera() {
        Log.d(TAG, "startCamera")

        setCamera1Parameters()

        val threadName = "camera-handler-thread"
        cameraThread = HandlerThread(threadName)
        cameraThread!!.start()
        val cameraThreadHandler = Handler(cameraThread!!.getLooper())

        cameraManager = this.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraIdList = cameraManager!!.cameraIdList

        Logging.d(TAG, "cameraIdList = ${cameraIdList.joinToString(separator = ", ")}")
        cameraId = cameraIdList[0]

        cameraManager!!.openCamera(cameraId!!, cameraStateCallback, cameraThreadHandler)
    }

    private fun setCamera1Parameters() {
        val deviceModel = Build.MODEL
        Logging.d(TAG, "deviceModel=${deviceModel}")
        if(deviceModel.equals("RICOH THETA V")) {
            Logging.d(TAG, "Try to set camera1 parameters")
            val camera1 = Camera.open()

            val camera1Parameters = camera1.parameters
            camera1Parameters.set("RIC_SHOOTING_MODE", ThetaCapturer.ShootingMode.RIC_MOVIE_PREVIEW_3840.value)
            camera1.parameters = camera1Parameters
            camera1Parameters.set("RIC_SHOOTING_MODE", ThetaCapturer.ShootingMode.RIC_MOVIE_RECORDING_4K_EQUI.value)
            // camera1Parameters.set("RIC_PROC_STITCHING", "RicNonStitching");
            camera1Parameters.set("RIC_PROC_STITCHING", "RicStaticStitching")
            // camera1Parameters.set("RIC_PROC_STITCHING", "RicDynamicStitchingAuto")

            camera1Parameters.set("video-size", "3840x1920")
            camera1Parameters.setPreviewSize(3840, 1920)
            camera1.parameters = camera1Parameters
        }
    }

 }
