// Copyright 2018 Ricoh Company, Ltd. All rights reserved.
// Copyright 2018 Shiguredo, Inc. All rights reserved.

package com.theta360.sample.livestreaming

import android.content.Context
import android.content.Intent
import android.hardware.Camera
import jp.shiguredo.sora.sdk.util.SoraLogger
import org.webrtc.CapturerObserver

import org.webrtc.SurfaceTextureHelper
import org.webrtc.ThreadUtils
import org.webrtc.VideoCapturer

class ThetaCapturer(
        private val shootingMode: ShootingMode,
        private val maintainsResolution : Boolean = false
) : VideoCapturer {
    companion object {
        fun actionMainCameraClose(context: Context) {
            context.sendBroadcast(Intent("com.theta360.plugin.ACTION_MAIN_CAMERA_CLOSE"))
        }

        fun actionMainCameraOpen(context: Context) {
            context.sendBroadcast(Intent("com.theta360.plugin.ACTION_MAIN_CAMERA_OPEN"))
        }
    }

    private var camera: Camera? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var capturerObserver: CapturerObserver? = null


    override fun initialize(surfaceTextureHelper: SurfaceTextureHelper, context: Context, capturerObserver: CapturerObserver) {
        this.surfaceTextureHelper = surfaceTextureHelper
        this.capturerObserver = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        this.camera = Camera.open()

        surfaceTextureHelper!!
                .surfaceTexture
                .setDefaultBufferSize(shootingMode.width, shootingMode.height)
        surfaceTextureHelper!!.setTextureSize(shootingMode.width, shootingMode.height)
        camera!!.setPreviewTexture(surfaceTextureHelper!!.surfaceTexture)
        // val supportedPreviewFrameRates = camera!!.parameters.getSupportedPreviewFrameRates()
        // SoraLogger.d("ThetaCapturer", "supported fps: ${supportedPreviewFrameRates}")
        val params = camera!!.parameters.apply {
            set("RIC_SHOOTING_MODE", shootingMode.value)

            set("RIC_PROC_STITCHING", "RicNonStitching")
            // set("RIC_PROC_STITCHING", "RicStaticStitching")
            // set("RIC_PROC_STITCHING", "RicDynamicStitchingAuto")
            // set("RIC_PROC_STITCHING", "RicDynamicStitchingSave")
            // set("RIC_PROC_STITCHING", "RicDynamicStitchingLoad")

            // set("RIC_EXPOSURE_MODE", "RicManualExposure")
            // set("RIC_EXPOSURE_MODE", "RicAutoExposureP")

            // set("RIC_WB_MODE", "RicWbManualGain")
            // set("RIC_MANUAL_EXPOSURE_ISO_FRONT", -1)
            // set("RIC_MANUAL_EXPOSURE_ISO_REAR", -1)

            // set("RIC_EXPOSURE_MODE", "RicAutoExposureT")
            // set("RIC_MANUAL_EXPOSURE_TIME_FRONT", 0)
            // set("RIC_MANUAL_EXPOSURE_TIME_REAR", 0)
            previewFrameRate = framerate

            // Do not set, or Die with fatal exception
            // setRecordingHint(true)
        }
        camera!!.parameters = params
        camera!!.startPreview()

        capturerObserver!!.onCapturerStarted(true)
        surfaceTextureHelper!!.startListening(capturerObserver!!::onFrameCaptured)
    }

    override fun stopCapture() {
        ThreadUtils.invokeAtFrontUninterruptibly(surfaceTextureHelper!!.handler) {
            camera!!.stopPreview()
            surfaceTextureHelper!!.stopListening()
            capturerObserver!!.onCapturerStopped()
        }
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        throw UnsupportedOperationException("changeCaptureFormat is not supported.")
    }

    override fun dispose() {
        camera?.release()
        camera = null
    }

    override fun isScreencast(): Boolean = maintainsResolution

    enum class ShootingMode(
            val value: String,
            val width: Int,
            val height: Int,
            val videoSize: String
    ) {
        RIC_MOVIE_PREVIEW_640("RicMoviePreview640", 640, 320, "640x320"),
        RIC_MOVIE_PREVIEW_1024("RicMoviePreview1024", 1024, 512, "1024x512"),
        RIC_MOVIE_PREVIEW_1920("RicMoviePreview1920", 1920, 960, "1920x960"),
        RIC_MOVIE_PREVIEW_3840("RicMoviePreview3840", 3840, 1920, "3840x1920"),

        RIC_MOVIE_RECORDING_4K_EQUI("RicMovieRecording4kEqui", 3840, 1920, "3840x1920"),
        RIC_MOVIE_RECORDING_4K_DUAL("RicMovieRecording4kDual", 3840, 1920, "3840x1920"),
        RIC_MOVIE_RECORDING_2K_EQUI("RicMovieRecording2kEqui", 1920, 960, "1920x960"),
        RIC_MOVIE_RECORDING_2K_DUAL("RicMovieRecording2kDual", 1920, 960, "1920x960")
    }
}
