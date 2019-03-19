// Copyright 2018 Ricoh Company, Ltd. All rights reserved.
// Copyright 2018 Shiguredo, Inc. All rights reserved.

package com.theta360.sample.livestreaming

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.hardware.Camera
import android.hardware.camera2.*
import android.media.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import jp.shiguredo.sora.sdk.util.SoraLogger
import org.webrtc.Logging
import java.lang.Exception
import java.lang.RuntimeException


@Suppress("DEPRECATION")
class Camera2ToEncodeActivity : Activity() {
    companion object {
        private val TAG = Camera2ToEncodeActivity::class.simpleName
    }

    enum class OutputTarget {
        BOTH,
        ENCODER,
        VIEW
    }

    private val outputTarget: OutputTarget = OutputTarget.ENCODER

    // Capture parameters
    // private val shootingMode = ThetaCapturer.ShootingMode.RIC_MOVIE_PREVIEW_3840
    private val shootingMode = ShootingMode.RIC_MOVIE_RECORDING_4K_EQUI
    // private val shootingMode = ThetaCapturer.ShootingMode.RIC_MOVIE_RECORDING_4K_DUAL
    // private val shootingMode = ThetaCapturer.ShootingMode.RIC_MOVIE_RECORDING_2K_EQUI
    // private val shootingMode = ThetaCapturer.ShootingMode.RIC_MOVIE_RECORDING_2K_DUAL
    // private val shootingMode = ThetaCapturer.ShootingMode.RIC_STILL_PREVIEW_1920
    private val frameRate = 30


    private var encoder: MediaCodec? = null;
    private var encoderInputSurface: Surface? = null


    private var cameraThread: HandlerThread? = null
    private val cameraThreadHandler: Handler? = null

    private var cameraManager: CameraManager? = null
    private var cameraId: String? = null
    private var camera: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var captureRequest: CaptureRequest? = null

    private var surfaceView: SurfaceView? = null

    private var encoderOutputThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        SoraLogger.enabled = true
        SoraLogger.libjingle_enabled = true

        setContentView(R.layout.activity_camera)
        surfaceView = findViewById(R.id.surfaceView)
   }

    override fun onResume() {
        super.onResume()

        setupThetaDevices()

        startEncoder()
        surfaceView!!.holder.addCallback(object: SurfaceHolder.Callback {
            override fun surfaceCreated(surfaceHolder: SurfaceHolder) {
                Logging.d(TAG, "surfaceCreated")
                surfaceHolder.setFixedSize(shootingMode.width, shootingMode.width)
                // startCamera()
            }

            override fun surfaceChanged(surfaceHolder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Logging.d(TAG, "surfaceChanged: format=$format, ${width}x$height")
                if (width == shootingMode.width) {
                    startCamera()
                }
            }

            override fun surfaceDestroyed(surfaceHolder: SurfaceHolder) {
                Logging.d(TAG, "surfaceDestroyed")
            }
        })
     }

    override fun onPause() {
        // Configures RICOH THETA's camera. This is not a general Android configuration.
        // see https://api.ricoh/docs/theta-plugin-reference/broadcast-intent/#notifying-camera-device-control
        SoraLogger.d(TAG, "Broadcast ACTION_MAIN_CAMERA_OPEN")
        ThetaCapturer.actionMainCameraOpen(applicationContext)

        cameraThread?.quit()

        super.onPause()
    }

    private fun startEncoder() {
        val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC

        for (codecInfo in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
            if (!codecInfo.isEncoder) continue

            val types = codecInfo.supportedTypes
            for (type in types) {
                // Logging.d(TAG, "type=${type}")
                if (type != mimeType) continue

                val c = codecInfo.getCapabilitiesForType(type)
                val v = c.videoCapabilities ?: continue
                val e = c.encoderCapabilities
                val w = fun(F : () -> Any): Any {
                    return try { F() } catch (e: Exception) { "not supported" } }

                Logging.d(TAG, """

                    ================== type=$type codec capabilities ====================
                    defaultFormat:            ${c.defaultFormat}
                    colorFormats:             ${c.colorFormats.joinToString(", ")}
                    maxSupportedInstances:    ${c.maxSupportedInstances}
                    profileLevels:            ${c.profileLevels.map { "${it.level}/${it.profile}" }.joinToString(", ")}
                    ================== type=$type video capabilities ====================
                    bitrateRange:             ${v.bitrateRange}
                    supportedFrameRates:      ${v.supportedFrameRates}
                    supportedWidths:          ${v.supportedWidths}
                    supportedHeights:         ${v.supportedHeights}
                    For 3840x1920
                    frameRatesFor:            ${w {v.getSupportedFrameRatesFor(3840, 1920)}}
                    archievableFrameRatesFor: ${w {v.getAchievableFrameRatesFor(3840, 1920)}}
                    ================== type=$type encoder capabilities ==================
                    qualityRange:             ${e.qualityRange}
                    complexityRange:          ${e.complexityRange}
                    =====================================================================
                """.trimIndent())
            }

            // throw RuntimeException("dummy")
        }


        // val mediaCodecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        // mediaCodecList.findEncoderForFormat(mediaFormat1)



        encoder = MediaCodec.createEncoderByType(mimeType);
        val mediaFormat = MediaFormat.createVideoFormat(mimeType,
                shootingMode.width, shootingMode.height)
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 3*1000*1000)
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 20)
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        // val OMX_QCOM_COLOR_FormatYUV420PackedSemiPlanar32m = 2141391876
        // mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)

        // mediaFormat.setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_IntraRefresh, false)

        encoder!!.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderInputSurface = encoder!!.createInputSurface()
        encoder!!.start()

        val encoderForConsumer = encoder!!

        encoderOutputThread = object: Thread(){
            override fun run() {
                while (true) {
                    consumeOutputBuffer(encoderForConsumer)
                }
            }
        }
        encoderOutputThread!!.start()
    }

    private fun consumeOutputBuffer(encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        val index = encoder.dequeueOutputBuffer(info, 3*1000*1000)

        if (index < 0) {
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                Logging.d(TAG, "consumeOutputBuffer: negative index=${index} (INFO_TRY_AGAIN_LATER)");
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Logging.d(TAG, "consumeOutputBuffer: negative index=${index} (INFO_OUTPUT_FORMAT_CHANGED)");
            } else if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                Logging.d(TAG, "consumeOutputBuffer: negative index=${index} (INFO_OUTPUT_BUFFERS_CHANGED)");
            } else {
                Logging.d(TAG, "consumeOutputBuffer: negative index=${index} (SHOULD NEVER HAPPEN)");
            }
            return;
        }

        // Logging.d(TAG, "codecOutputBuffers index/length=" + index + "/" + outputBuffersSize);
        val codecOutputBuffer = encoder.getOutputBuffer(index);
        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            Logging.d(TAG, "consumeOutputBuffer: config frame, index=${index}, info.offset=${info.offset}, info.size=${info.size}");
        }
        // Logging.d(TAG, "consumeOutputBuffer: index=${index}, info.offset=${info.offset}, info.size=${info.size}");

        encoder.releaseOutputBuffer(index, false);

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
            val outputs = when (outputTarget) {
                OutputTarget.BOTH ->
                    listOf(encoderInputSurface, surfaceView!!.holder.surface)
                OutputTarget.VIEW ->
                    listOf(surfaceView!!.holder.surface)
                OutputTarget.ENCODER ->
                    listOf(encoderInputSurface)
            }
            Logging.d(TAG, "Camera outputs: ${outputs}")
            camera!!.createCaptureSession(outputs, captureSessionCallback,
                    cameraThreadHandler)
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
            when (outputTarget) {
                OutputTarget.BOTH -> {
                    captureRequestBuilder.addTarget(encoderInputSurface)
                    captureRequestBuilder.addTarget(surfaceView!!.holder.surface)
                }
                OutputTarget.ENCODER ->
                    captureRequestBuilder.addTarget(encoderInputSurface)
                OutputTarget.VIEW ->
                    captureRequestBuilder.addTarget(surfaceView!!.holder.surface)
            }

            captureRequest = captureRequestBuilder.build()
            session.setRepeatingRequest(captureRequest, captureCallback, Handler())
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
                Logging.d(TAG, "%.1f FPS".format(1000.0*fpsIntervalFramesTarget/(currentMillis - fpsIntervalStartMills)))
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
            camera1Parameters.set("RIC_SHOOTING_MODE", ShootingMode.RIC_MOVIE_PREVIEW_3840.value)
            camera1.parameters = camera1Parameters
            camera1Parameters.set("RIC_SHOOTING_MODE", ShootingMode.RIC_MOVIE_RECORDING_4K_EQUI.value)
            // camera1Parameters.set("RIC_PROC_STITCHING", "RicNonStitching");
            camera1Parameters.set("RIC_PROC_STITCHING", "RicStaticStitching")
            // camera1Parameters.set("RIC_PROC_STITCHING", "RicDynamicStitchingAuto")

            camera1Parameters.set("video-size", "3840x1920")
            camera1Parameters.setPreviewSize(3840, 1920)
            // camera1Parameters.set("recording-hint", "true")
            camera1.parameters = camera1Parameters
        }
    }

 }
