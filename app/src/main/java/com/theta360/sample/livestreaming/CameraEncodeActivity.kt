// Copyright 2018 Ricoh Company, Ltd. All rights reserved.
// Copyright 2018 Shiguredo, Inc. All rights reserved.

package com.theta360.sample.livestreaming

import android.app.Activity
import android.hardware.Camera
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.util.Log
import jp.shiguredo.sora.sdk.util.SoraLogger
import org.webrtc.*
import org.webrtc.Logging
import org.webrtc.ThreadUtils
import android.os.HandlerThread
import android.graphics.SurfaceTexture
import android.hardware.Camera.Parameters.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.GLES11Ext
import org.webrtc.GlUtil
import org.webrtc.GlUtil.checkNoGLES2Error
import android.opengl.GLES20
import android.view.Surface
import java.lang.Exception


@Suppress("DEPRECATION")
class CameraEncodeActivity : Activity() {
    companion object {
        private val TAG = CameraEncodeActivity::class.simpleName
    }

    // Capture parameters
    // private val shootingMode = ThetaCapturer.ShootingMode.RIC_MOVIE_PREVIEW_3840
    private val shootingMode = ShootingMode.RIC_MOVIE_RECORDING_4K_EQUI
    // private val shootingMode = ThetaCapturer.ShootingMode.RIC_MOVIE_RECORDING_4K_DUAL
    // private val shootingMode = ThetaCapturer.ShootingMode.RIC_MOVIE_RECORDING_2K_EQUI
    // private val shootingMode = ThetaCapturer.ShootingMode.RIC_MOVIE_RECORDING_2K_DUAL
    // private val shootingMode = ThetaCapturer.ShootingMode.RIC_STILL_PREVIEW_1920
    private val frameRate = 30

    private var camera: Camera? = null;

    private var encoder: MediaCodec? = null;
    private var encoderInputSurface: Surface? = null

    private var eglBase: EglBase? = null
    private var eglBaseContext: EglBase.Context? = null

    private var surfaceTexture: SurfaceTexture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        SoraLogger.enabled = true
        SoraLogger.libjingle_enabled = true

        setContentView(R.layout.activity_empty)
    }

    override fun onResume() {
        super.onResume()

        eglBase = EglBase.create()
        eglBaseContext = eglBase!!.eglBaseContext
        setupTexture()
        setupThetaDevices()
        startEncoder()
        startCamera()
    }

    override fun onPause() {
        super.onPause()

        camera?.stopPreview()
        camera?.release()
        camera = null
        surfaceTexture?.release()
        // Configures RICOH THETA's camera. This is not a general Android configuration.
        // see https://api.ricoh/docs/theta-plugin-reference/broadcast-intent/#notifying-camera-device-control
        SoraLogger.d(TAG, "Broadcast ACTION_MAIN_CAMERA_OPEN")
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
        SoraLogger.d(TAG, "Broadcast ACTION_MAIN_CAMERA_CLOSE")
        ThetaCapturer.actionMainCameraClose(applicationContext)
    }

    private fun setupTexture() {
        val threadName = "capture-surface-handler-thread"
        val thread = HandlerThread(threadName)
        thread.start()
        val handler = Handler(thread.getLooper())

        ThreadUtils.invokeAtFrontUninterruptibly(handler) {
            eglBase!!.createDummyPbufferSurface();
            eglBase!!.makeCurrent();
        }

        val oesTextureId = GlUtil.generateTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        var lastFrameCaptured = System.currentTimeMillis()
        val fpsIntervalFramesTarget = 30
        var fpsIntevalStart = lastFrameCaptured
        var fpsIntervalFrames = 0
        surfaceTexture = SurfaceTexture(oesTextureId)
        surfaceTexture!!.setOnFrameAvailableListener(
                {_ ->
                    surfaceTexture!!.updateTexImage()
                    val current = System.currentTimeMillis()
                    Logging.d(TAG, "Camera image captured. interval=${current - lastFrameCaptured} msec")
                    lastFrameCaptured = current
                    if (fpsIntervalFrames != fpsIntervalFramesTarget) {
                        fpsIntervalFrames++
                    } else {
                        Logging.d(TAG, "%.1f FPS".format(1000.0*fpsIntervalFramesTarget/(current - fpsIntevalStart)))
                        fpsIntervalFrames = 0
                        fpsIntevalStart = current
                    }
                    Unit
                },
                handler)
        surfaceTexture!!.setDefaultBufferSize(shootingMode.width, shootingMode.height)
    }

    private fun startEncoder() {
        val codecName = "video/avc"
        encoder = MediaCodec.createByCodecName(codecName);
        val mediaFormat = MediaFormat.createVideoFormat(codecName, shootingMode.width,
                shootingMode.height)
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 15*1000*1000)
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 20)
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        encoder!!.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderInputSurface = encoder!!.createInputSurface()
        encoder!!.start()
    }

    private fun startCamera() {
        Log.d(TAG, "startCamera")
        val cameraId= 0;

        for (i in 1..10) {
            try {
                camera = android.hardware.Camera.open(cameraId)
                break
            } catch (e: Exception) {
                Logging.d(TAG, "Ignore error in opening camera: $e")
                Thread.sleep(100)
            }
        }
        if (camera != null) {
            Log.d(TAG, "Camera opened.")
        } else {
            Log.e(TAG, "Camera open failed.")
            throw RuntimeException()
        }

        val parameters = camera!!.parameters

        // Sometimes, maybe just after restart?, camera emits no preview with RicMovieRecording4kEqui.
        // Once set to RicMoviePreview3840 here. It will be overwritten afterward.
        parameters.set("RIC_SHOOTING_MODE",
                ShootingMode.RIC_MOVIE_PREVIEW_3840.value)
        camera!!.parameters = parameters

        parameters.previewFrameRate = frameRate

        parameters.focusMode = FOCUS_MODE_CONTINUOUS_VIDEO
        parameters.set("face-detection", 0)

        // This does NOT work.
        // parameters.setPreviewFpsRange(frameRate, frameRate);

        // parameters.set("RIC_SHOOTING_MODE", shootingMode.value)
        // Any effect? At least, it seems do no harm.
        // parameters.set("video-size", shootingMode.getVideoSize());
        // parameters.set("video-size", "5376x2688");
        // If recording-hint is set to true, camera become frozen.
        // parameters.set("recording-hint", "true");

        parameters.set("secure-mode", "disable")
        parameters.set("zsl", 1)

        // It seems the same as "recording-hint" above. Do not set this true.
        // parameters.setRecordingHint(true)

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
        parameters.setPictureSize(5376, 2688);

        if (parameters.isVideoStabilizationSupported) {
            // parameters.videoStabilization = true
        }
        // if (focusModes.contains(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
        //     parameters.setFocusMode(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        // }

        camera!!.parameters = parameters
        camera!!.setPreviewTexture(surfaceTexture)
        camera!!.startPreview()
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

 }
