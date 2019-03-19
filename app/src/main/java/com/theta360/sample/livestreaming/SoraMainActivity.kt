// Copyright 2018 Ricoh Company, Ltd. All rights reserved.
// Copyright 2018 Shiguredo, Inc. All rights reserved.

package com.theta360.sample.livestreaming

import android.app.Activity
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import jp.shiguredo.sora.sdk.channel.SoraMediaChannel
import jp.shiguredo.sora.sdk.channel.option.SoraMediaOption
import jp.shiguredo.sora.sdk.channel.option.SoraVideoOption
import jp.shiguredo.sora.sdk.channel.signaling.message.PushMessage
import jp.shiguredo.sora.sdk.error.SoraErrorReason
import jp.shiguredo.sora.sdk.util.SoraLogger
import org.webrtc.*


class SoraMainActivity : Activity() {
    companion object {
        private val TAG = SoraMainActivity::class.simpleName
    }

    // Capture parameters
    // private val shootingMode = ThetaCapturer.ShootingMode.RIC_MOVIE_PREVIEW_3840
    private val shootingMode = ShootingMode.RIC_MOVIE_RECORDING_4K_EQUI
    // private val shootingMode = ThetaCapturer.ShootingMode.RIC_MOVIE_RECORDING_4K_DUAL
    // private val shootingMode = ThetaCapturer.ShootingMode.RIC_MOVIE_RECORDING_2K_EQUI
    // private val shootingMode = ThetaCapturer.ShootingMode.RIC_MOVIE_RECORDING_2K_DUAL
    // private val shootingMode = ThetaCapturer.ShootingMode.RIC_STILL_PREVIEW_1920
    private val frameRate = 30
    private val maintainsResolution = true

    // signaling parameters for video
    private val bitRate = 15000
    private val codec = SoraVideoOption.Codec.H264

    private var localView: SurfaceViewRenderer? = null
    private var capturer: VideoCapturer? = null
    private var eglBase: EglBase? = null

    private var channel: SoraMediaChannel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        SoraLogger.enabled = true
        SoraLogger.libjingle_enabled = true

        setContentView(R.layout.activity_main)
        localView = findViewById(R.id.local_view)

        eglBase = EglBase.create()
        // localView!!.init(eglBase!!.eglBaseContext, null)
    }

    override fun onResume() {
        super.onResume()
        setupThetaDevices()
        startChannel()
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

    private val channelListener = object : SoraMediaChannel.Listener {

        override fun onConnect(mediaChannel: SoraMediaChannel) {
            Log.d(TAG, "onConnect")
        }

        override fun onClose(mediaChannel: SoraMediaChannel) {
            Log.d(TAG, "onClose")
            close()
        }

        override fun onError(mediaChannel: SoraMediaChannel, reason: SoraErrorReason) {
            Log.e(TAG, "onError: ${reason}")
            close()
        }

        override fun onAddRemoteStream(mediaChannel: SoraMediaChannel, ms: MediaStream) {
            Log.d(TAG, "onAddRemoteStream")
            // nop
        }

        override fun onAddLocalStream(mediaChannel: SoraMediaChannel, ms: MediaStream) {
            Log.d(TAG, "onAddLocalStream")
            runOnUiThread {
                if (ms.videoTracks.size > 0) {
                    // val track = ms.videoTracks[0]
                    // track.addSink(this@SoraMainActivity.localView)
                }
                capturer?.startCapture(shootingMode.width, shootingMode.height, frameRate)
            }
        }

        override fun onPushMessage(mediaChannel: SoraMediaChannel, push: PushMessage) {
            // Log.d(TAG, "onPushMessage: push=${push}")
            // val data = push.data
            // if(data is Map<*, *>) {
            //     data.forEach { (key, value) ->
            //         Log.d(TAG, "received push data: $key=$value")
            //     }
            // }
        }
    }

    private fun startChannel() {
        Log.d(TAG, "startChannel")

        val camera1Enumerator = Camera1Enumerator()
        val deviceNames = camera1Enumerator.deviceNames
        for (deviceName in deviceNames) {
            SoraLogger.d(TAG, "camera device name: ${deviceName}")
        }
        val cameraName = deviceNames[0]
        capturer = ThetaCamera1Capturer(shootingMode, cameraName,
               /* CameraEventsHandler */ null,
               /*captureToTexture*/ true,
                maintainsResolution)

        // capturer = ThetaCapturer(shootingMode, maintainsResolution)

        val thetaVideoEncoderFactory = ThetaHardwareVideoEncoderFactory(
                eglBase!!.eglBaseContext,
                true /* enableIntelVp8Encoder */,
                false /* enableH264HighProfile */)
        val option = SoraMediaOption().apply {
            // enableAudioUpstream()
            // audioCodec = SoraAudioOption.Codec.OPUS

            enableVideoUpstream(capturer!!, eglBase!!.eglBaseContext)
            videoCodec = codec
            videoBitrate = bitRate
            enableCpuOveruseDetection = false
            videoEncoderFactory = thetaVideoEncoderFactory
        }

        channel = SoraMediaChannel(
                context           = this,
                signalingEndpoint = BuildConfig.SIGNALING_ENDPOINT,
                channelId         = BuildConfig.CHANNEL_ID,
                mediaOption       = option,
                listener          = channelListener)
        channel!!.connect()
    }

    private fun close() {
        channel?.disconnect()
        channel = null

        capturer?.stopCapture()
        capturer = null

        localView?.release()

        eglBase?.release()
        eglBase = null

        // Configures RICOH THETA's camera. This is not a general Android configuration.
        // see https://api.ricoh/docs/theta-plugin-reference/broadcast-intent/#notifying-camera-device-control
        SoraLogger.d(TAG, "Broadcast ACTION_MAIN_CAMERA_OPEN")
        ThetaCapturer.actionMainCameraOpen(applicationContext)
    }

}
