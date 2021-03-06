// Copyright 2018 Ricoh Company, Ltd. All rights reserved.
// Copyright 2018 Shiguredo, Inc. All rights reserved.

package com.theta360.sample.livestreaming

import android.app.Activity
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.media.AudioManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import org.webrtc.EglBase
import java.util.concurrent.CountDownLatch
import javax.microedition.khronos.egl.EGL10


@Suppress("DEPRECATION")
class Camera1ToSurfaceTextureActivity : Activity() {
    companion object {
        private val TAG = Camera1ToSurfaceTextureActivity::class.simpleName

        private const val EGL_OPENGL_ES2_BIT = 4
        // Android-specific extension.
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private val CONFIG_PIXEL_RGBA_BUFFER_RECORDABLE = intArrayOf(
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL10.EGL_NONE
        )

        private fun logD(message: String) {
            Log.d(TAG, message)
        }
    }

    // Capture parameters
    // private val shootingMode = ShootingMode.RIC_MOVIE_PREVIEW_3840
    private val shootingMode = ShootingMode.RIC_MOVIE_RECORDING_4K_EQUI
    // private val shootingMode = ShootingMode.RIC_MOVIE_RECORDING_4K_DUAL
    // private val shootingMode = ShootingMode.RIC_MOVIE_RECORDING_2K_EQUI
    // private val shootingMode = ShootingMode.RIC_MOVIE_RECORDING_2K_DUAL
    // private val shootingMode = ThetaCapturer.ShootingMode.RIC_STILL_PREVIEW_1920
    private val frameRate = 30

    private var camera: Camera? = null;

    private var eglBase: EglBase? = null
    private var eglBaseContext: EglBase.Context? = null

    // single buffer mode does NOT work with camera1
    //   [SurfaceTexture-0-6448-0] setMaxDequeuedBufferCount: 5 dequeued buffers would exceed the maxBufferCount (1)
    //   (maxAcquired 1 async 0 mDequeuedBufferCannotBlock 0)
    private val singleBufferMode = false
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    private var textureCaptureThread: HandlerThread? = null
    private var textureCaptureHandler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_empty)
    }

    override fun onResume() {
        super.onResume()

        eglBase = EglBase.create()
        eglBaseContext = eglBase!!.eglBaseContext
        setupTexture()
        setupThetaDevices()
        startCamera()
    }

    override fun onPause() {
        super.onPause()

        camera?.stopPreview()
        camera?.release()
        camera = null
        surfaceTexture?.release()

        textureCaptureThread?.quit()

        // Configures RICOH THETA's camera. This is not a general Android configuration.
        // see https://api.ricoh/docs/theta-plugin-reference/broadcast-intent/#notifying-camera-device-control
        logD("Broadcast ACTION_MAIN_CAMERA_OPEN")
        ThetaCapturer.actionMainCameraOpen(applicationContext)

        eglBase?.release()
        eglBase = null
    }

    private fun setupThetaDevices() {
        // Configures RICOH THETA's microphone and camera. This is not a general Android configuration.
        // see https://api.ricoh/docs/theta-plugin-reference/audio-manager-api/
        // see https://api.ricoh/docs/theta-plugin-reference/broadcast-intent/#notifying-camera-device-control

        // recording in monaural
        (getSystemService(AUDIO_SERVICE) as AudioManager)
                .setParameters("RicUseBFormat=false")
        // Prepare to use camera
        logD("Broadcast ACTION_MAIN_CAMERA_CLOSE")
        ThetaCapturer.actionMainCameraClose(applicationContext)
    }

    private fun setupTexture() {
        val threadName = "capture-surface-handler-thread"
        textureCaptureThread = HandlerThread(threadName)
        textureCaptureThread!!.start()
        textureCaptureHandler = Handler(textureCaptureThread!!.getLooper())

        invokeAtFrontUninterruptibly(textureCaptureHandler!!) {
            eglBase!!.createDummyPbufferSurface();
            eglBase!!.makeCurrent();
        }

        val oesTextureId = generateTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        var lastFrameCaptured = System.currentTimeMillis()
        val fpsIntervalFramesTarget = 30
        var fpsIntervalStart = lastFrameCaptured
        var fpsIntervalFrames = 0
        surfaceTexture = SurfaceTexture(oesTextureId, singleBufferMode)
        surfaceTexture!!.setDefaultBufferSize(shootingMode.width, shootingMode.height)
        surfaceTexture!!.setOnFrameAvailableListener(
                {_ ->
                    surfaceTexture!!.updateTexImage()
                    if(singleBufferMode) {
                        surfaceTexture!!.releaseTexImage()
                    }
                    val current = System.currentTimeMillis()
                    logD("SurfaceTexture frame available. interval=${current - lastFrameCaptured} msec")
                    lastFrameCaptured = current
                    fpsIntervalFrames++
                    if (fpsIntervalFrames == fpsIntervalFramesTarget) {
                        logD("SurfaceTexture: %.1f FPS".format(1000.0*fpsIntervalFramesTarget/(current - fpsIntervalStart)))
                        fpsIntervalFrames = 0
                        fpsIntervalStart = current
                    }
                    Unit
                },
                textureCaptureHandler)
        surface = Surface(surfaceTexture!!)
    }

    private fun startCamera() {
        Log.d(TAG, "startCamera")
        val threadName = "camera-handler-thread"
        val thread = HandlerThread(threadName)
        thread.start()
        val handler = Handler(thread.getLooper())

        handler.post {
            val cameraId= 0;

            for (i in 1..10) {
                try {
                    camera = android.hardware.Camera.open(cameraId)
                    break
                } catch (e: Exception) {
                    logD("Ignore error in opening camera: $e")
                    Thread.sleep(100)
                }
            }
            if (camera != null) {
                logD("Camera opened.")
            } else {
                logD("Camera open failed.")
                throw RuntimeException()
            }

            val parameters = camera!!.parameters

            // Sometimes, maybe just after restart?, camera emits no preview with RicMovieRecording4kEqui.
            // Once set to RicMoviePreview3840 here. It will be overwritten afterward.
            parameters.set("RIC_SHOOTING_MODE",
                    ShootingMode.RIC_MOVIE_PREVIEW_3840.value)
            camera!!.parameters = parameters

            parameters.previewFrameRate = frameRate

            // parameters.focusMode = FOCUS_MODE_CONTINUOUS_VIDEO
            // parameters.set("face-detection", 0)

            // This does NOT work.
            // parameters.setPreviewFpsRange(frameRate, frameRate);

            parameters.set("RIC_SHOOTING_MODE", shootingMode.value)
            // Any effect? At least, it seems do no harm.
            // parameters.set("video-size", shootingMode.getVideoSize());
            // parameters.set("video-size", "5376x2688");
            parameters.set("video-size", "3840x1920")

            parameters.setPreviewSize(3840, 1920)
            // parameters.setPreviewSize(640, 480)
            // parameters.setPreviewSize(5376, 2688)

            // If recording-hint is set to true, camera become frozen.
            parameters.set("recording-hint", "true");
            // It seems the same as "recording-hint" above. Do not set this true.
            // parameters.setRecordingHint(true)

            // parameters.set("secure-mode", "disable")
            // parameters.set("zsl", 1)

            // parameters.set("RIC_PROC_STITCHING", "RicNonStitching");
            parameters.set("RIC_PROC_STITCHING", "RicStaticStitching")
            // parameters.set("RIC_PROC_STITCHING", "RicDynamicStitchingAuto");
            // parameters.set("RIC_PROC_STITCHING", "RicDynamicStitchingSave");
            // parameters.set("RIC_PROC_STITCHING", "RicDynamicStitchingLoad");

            // parameters.set("RIC_EXPOSURE_MODE", "RicManualExposure");
            // parameters.set("RIC_EXPOSURE_MODE", "RicAutoExposureP");
            // parameters.set("RIC_EXPOSURE_MODE", "RicAutoExposureS");
            // parameters.set("RIC_EXPOSURE_MODE", "RicAutoExposureT");
            // parameters.set("RIC_EXPOSURE_MODE", "RicAutoExposureWDR");

            // parameters.set("RIC_WB_MODE", "RicWbManualGain");
            // parameters.set("RIC_WB_TEMPERATURE", 10000);
            // parameters.set("RIC_MANUAL_EXPOSURE_ISO_FRONT", -1);
            // parameters.set("RIC_MANUAL_EXPOSURE_ISO_REAR", -1);

            // parameters.set("RIC_EXPOSURE_MODE", "RicAutoExposureT");
            // parameters.set("RIC_MANUAL_EXPOSURE_TIME_FRONT", 0);
            // parameters.set("RIC_MANUAL_EXPOSURE_TIME_REAR", 0);

            // parameters.setPreviewSize(shootingMode.width, shootingMode.height)
            // What are these numbers?
            // parameters.setPreviewSize(5376, 2688);

            // No need for this? I guess only preview is used.
            // Almost marginal but maybe slightly better FPS when set.
            // parameters.setPictureSize(shootingMode.width, shootingMode.height)
            // parameters.setPictureSize(5376, 2688);

            if (parameters.isVideoStabilizationSupported) {
                // parameters.videoStabilization = true
            }
            // if (focusModes.contains(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            //     parameters.setFocusMode(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            // }

            camera!!.parameters = parameters
            camera!!.setPreviewTexture(surfaceTexture)
            camera!!.startPreview()
            // camera!!.unlock()
        }
    }

    private fun generateTexture(target: Int): Int {
        val textureArray = IntArray(1)
        GLES20.glGenTextures(1, textureArray, 0)
        val textureId = textureArray[0]
        GLES20.glBindTexture(target, textureId)
        GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE.toFloat())
        GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE.toFloat())
        checkNoGLES2Error("generateTexture")
        return textureId
    }

    private fun checkNoGLES2Error(msg: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            throw RuntimeException("$msg: GLES20 error: $error")
        }
    }

    private fun invokeAtFrontUninterruptibly(handler: Handler, callable: () -> Unit) {
        var exceptionOccurred: Exception? = null
        val countDownLatch = CountDownLatch(1)
        handler.post {
            try {
                callable()
            } catch (e: Exception) {
                exceptionOccurred = e
            } finally {
                countDownLatch.countDown()
            }
        }

        countDownLatch.await()
        if (exceptionOccurred != null) {
            throw java.lang.RuntimeException(exceptionOccurred)
        }
    }

}
