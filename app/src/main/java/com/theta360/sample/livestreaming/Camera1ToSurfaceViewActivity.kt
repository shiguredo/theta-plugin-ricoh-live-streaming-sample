// Copyright 2018 Ricoh Company, Ltd. All rights reserved.
// Copyright 2018 Shiguredo, Inc. All rights reserved.

package com.theta360.sample.livestreaming

import android.app.Activity
import android.graphics.ImageFormat
import android.hardware.Camera
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import jp.shiguredo.sora.sdk.util.SoraLogger
import org.webrtc.Logging
import java.lang.RuntimeException


@Suppress("DEPRECATION")
class Camera1ToSurfaceViewActivity : Activity() {
    companion object {
        private val TAG = Camera1ToSurfaceViewActivity::class.simpleName

        private fun logD(message: String) {
            Log.d(TAG, message)
        }
    }

    // Capture parameters
    // private val shootingMode = ShootingMode.RIC_MOVIE_PREVIEW_3840
    // private val shootingMode = ShootingMode.RIC_MOVIE_PREVIEW_1920
    // private val shootingMode = ShootingMode.RIC_MOVIE_PREVIEW_640
    private val shootingMode = ShootingMode.RIC_MOVIE_RECORDING_4K_EQUI
    // private val shootingMode = ShootingMode.RIC_MOVIE_RECORDING_4K_DUAL
    // private val shootingMode = ShootingMode.RIC_MOVIE_RECORDING_2K_EQUI
    // private val shootingMode = ShootingMode.RIC_MOVIE_RECORDING_2K_DUAL
    // private val shootingMode = ShootingMode.RIC_STILL_PREVIEW_1920

    // private val stitchingMode = "RicNonStitching"
    private val stitchingMode = "RicStaticStitching"

    private val frameRate = 30

    private var cameraThread: HandlerThread? = null
    private var releaseThread: HandlerThread? = null
    private var releaseThreadHandler: Handler? = null

    private var camera: Camera? = null
    private val captureWithBuffer = true
    private val captureBufferCount = 1
    private val bufferReleaseDelayMSec = 0L

    private var surfaceView: SurfaceView? = null

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

        surfaceView!!.holder.addCallback(object: SurfaceHolder.Callback {
            override fun surfaceCreated(surfaceHolder: SurfaceHolder) {
                Logging.d(TAG, "surfaceCreated")
                surfaceHolder.setFixedSize(shootingMode.width, shootingMode.height)
                // startCamera()
            }

            override fun surfaceChanged(surfaceHolder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Logging.d(TAG, "surfaceChanged: format=$format, ${width}x$height")
                if (width == shootingMode.width) {
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
        releaseThread?.quit()

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

    private val previewCallback = object: Camera.PreviewCallback {
        var lastCapturedMillis = System.currentTimeMillis()
        val fpsIntervalFramesTarget = 10
        var fpsIntervalStartMills = lastCapturedMillis
        var fpsIntervalFrames = 0

        val fpsSummaryFramesTarget = 100
        var fpsSummaryStartMillis = lastCapturedMillis
        var fpsSummaryFrames = 0
        var fpsSummaryWarmUpFrames = 5

        override fun onPreviewFrame(byteArray: ByteArray?, camera: Camera?) {
            val currentMillis = System.currentTimeMillis()
            Logging.d(TAG, "PreviewCallback.onPreviewFrame: interval=${currentMillis - lastCapturedMillis} [msec], byteArray=${byteArray}")

            if (captureWithBuffer) {
                if (bufferReleaseDelayMSec == 0L) {
                    camera!!.addCallbackBuffer(byteArray)
                } else {
                    releaseThreadHandler!!.postDelayed({camera!!.addCallbackBuffer(byteArray)},
                            bufferReleaseDelayMSec)
                }
            }
            lastCapturedMillis = currentMillis
            fpsIntervalFrames++
            if (fpsIntervalFrames == fpsIntervalFramesTarget) {
                Logging.d(TAG, "%.1f FPS".format(1000.0*fpsIntervalFramesTarget/(currentMillis - fpsIntervalStartMills)))
                fpsIntervalFrames = 0
                fpsIntervalStartMills = currentMillis
            }

            if (fpsSummaryWarmUpFrames > 0) {
                fpsSummaryWarmUpFrames--
                if(fpsSummaryWarmUpFrames == 0) {
                    fpsSummaryStartMillis = currentMillis
                }
                return
            }

            fpsSummaryFrames++
            if (fpsSummaryFrames == fpsSummaryFramesTarget) {
                val fps = 1000.0*fpsSummaryFramesTarget/(currentMillis - fpsSummaryStartMillis)
                Logging.d(TAG, "Summary: %.1f FPS, %.1f [msec/frame]".format(fps, 1000.0/fps))
                fpsSummaryFrames = 0
                fpsSummaryStartMillis = currentMillis
            }
        }
    }


    private fun startCamera() {
        logD("startCamera")
        cameraThread = HandlerThread("camera-thread")
        cameraThread!!.start()

        releaseThread = HandlerThread("buffer-release-thread")
        releaseThread!!.start()
        releaseThreadHandler = Handler(releaseThread!!.looper)

        val cameraThreadHandler = Handler(cameraThread!!.looper)

        camera!!.stopPreview()

        cameraThreadHandler.post {

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
            // parameters.set("video-size", "3840x1920")

            parameters.setPreviewSize(shootingMode.width, shootingMode.height)
            // parameters.setPreviewSize(640, 480)
            // parameters.setPreviewSize(5376, 2688)

            // If recording-hint is set to true, camera become frozen.
            // parameters.set("recording-hint", "true");
            // It seems the same as "recording-hint" above. Do not set this true.
            // parameters.setRecordingHint(true)

            // parameters.set("secure-mode", "disable")
            // parameters.set("zsl", 1)

            parameters.set("RIC_PROC_STITCHING", stitchingMode)

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

            logD("Original preview format=${parameters.previewFormat}")
            parameters.previewFormat = ImageFormat.NV21
            camera!!.parameters = parameters

            val previewSize = parameters.previewSize
            logD("previewSize width=${previewSize.width}, height=${previewSize.height}")
            if (captureWithBuffer) {
                val bufferSize = previewSize.width * previewSize.height *
                        ImageFormat.getBitsPerPixel(parameters.previewFormat) / 2
                for (i in 1..captureBufferCount) {
                    val buffer = ByteArray(bufferSize)
                    logD("preview buffer added: index=${i}, buffer=${buffer}")
                    camera!!.addCallbackBuffer(buffer)
                }
            }

            camera!!.parameters = parameters
            logD("Surface behind SurfaceView isValid=${surfaceView!!.holder.surface.isValid}")
            camera!!.setPreviewDisplay(surfaceView!!.holder)
            if(captureWithBuffer) {
                camera!!.setPreviewCallbackWithBuffer(previewCallback)
            } else {
                camera!!.setPreviewCallback(previewCallback)
            }

            camera!!.startPreview()
            logD("Camera preview started.")

            // Don't call this!!
            // camera!!.unlock()
        }
    }

 }
