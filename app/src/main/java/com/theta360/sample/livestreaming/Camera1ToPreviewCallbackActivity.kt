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
import javax.microedition.khronos.egl.EGL10

// TODO: This does NOT working at present.

@Suppress("DEPRECATION")
class Camera1ToPreviewCallbackActivity : Activity() {
    companion object {
        private val TAG = Camera1ToPreviewCallbackActivity::class.simpleName

        private fun logD(message: String) {
            Log.d(TAG, message)
        }
    }

    // Capture parameters
    private val shootingMode = ShootingMode.RIC_MOVIE_PREVIEW_640
    // private val shootingMode = ShootingMode.RIC_MOVIE_RECORDING_4K_EQUI
    // private val shootingMode = ShootingMode.RIC_MOVIE_RECORDING_4K_DUAL
    // private val shootingMode = ShootingMode.RIC_MOVIE_RECORDING_2K_EQUI
    // private val shootingMode = ShootingMode.RIC_MOVIE_RECORDING_2K_DUAL
    // private val shootingMode = ShootingMode.RIC_STILL_PREVIEW_1920
    private val frameRate = 30

    private var camera: Camera? = null;
    private val captureBufferCount = 1
    private val captureWithBuffer = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_empty)
    }

    override fun onResume() {
        super.onResume()

        setupThetaDevices()
        startCamera()
    }

    override fun onPause() {
        super.onPause()

        camera?.setPreviewCallbackWithBuffer(null)
        camera?.stopPreview()
        camera?.release()
        camera = null

        // Configures RICOH THETA's camera. This is not a general Android configuration.
        // see https://api.ricoh/docs/theta-plugin-reference/broadcast-intent/#notifying-camera-device-control
        logD("Broadcast ACTION_MAIN_CAMERA_OPEN")
        ThetaCapturer.actionMainCameraOpen(applicationContext)
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

    private fun startCamera() {
        Log.d(TAG, "startCamera")
        val handler = Handler()

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
            camera!!.stopPreview()

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

            // parameters.setPreviewSize(3840, 1920)
            // parameters.setPreviewSize(640, 480)
            // parameters.setPreviewSize(5376, 2688)

            // If recording-hint is set to true, camera become frozen.
            // parameters.set("recording-hint", "true");
            // It seems the same as "recording-hint" above. Do not set this true.
            // parameters.setRecordingHint(true)

            // parameters.set("secure-mode", "disable")
            // parameters.set("zsl", 1)

            parameters.set("RIC_PROC_STITCHING", "RicNonStitching");
            // parameters.set("RIC_PROC_STITCHING", "RicStaticStitching")
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

            logD("Original preview format=${parameters.previewFormat}")
            parameters.previewFormat = ImageFormat.NV21
            camera!!.parameters = parameters

            val previewSize = parameters.previewSize
            logD("previewSize width=${previewSize.width}, height=${previewSize.height}")
            val bufferSize = previewSize.width * previewSize.height *
                    ImageFormat.getBitsPerPixel(parameters.previewFormat) / 2
            if (captureWithBuffer) {
                for (i in 1..captureBufferCount) {
                    val buffer = ByteArray(bufferSize)
                    logD("preview buffer added: index=${i}, buffer=${buffer}")
                    camera!!.addCallbackBuffer(buffer)
                }
                camera!!.setPreviewCallbackWithBuffer { buffer, camera ->
                    logD("previewCallback with buffer called: buffer=${buffer}")
                    camera.addCallbackBuffer(buffer)
                }
            } else {
                camera!!.setPreviewCallback {data, camera ->
                    logD("previewCallback called: data=${data}")
                }
            }

            camera!!.startPreview()
            logD("camera preview started.")
            // camera!!.unlock()
        }
    }

}
