// Copyright 2018 Ricoh Company, Ltd. All rights reserved.

package com.theta360.sample.livestreaming

import android.app.Activity
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import jp.shiguredo.sora.sdk.channel.SoraMediaChannel
import jp.shiguredo.sora.sdk.channel.option.SoraAudioOption
import jp.shiguredo.sora.sdk.channel.option.SoraMediaOption
import jp.shiguredo.sora.sdk.channel.option.SoraVideoOption
import jp.shiguredo.sora.sdk.channel.signaling.message.PushMessage
import jp.shiguredo.sora.sdk.error.SoraErrorReason
import jp.shiguredo.sora.sdk.util.SoraLogger
import org.webrtc.EglBase
import org.webrtc.MediaStream
import org.webrtc.SurfaceViewRenderer

class SoraMainActivity : Activity() {
    companion object {
        private val TAG = SoraMainActivity::class.simpleName
        private val SHOOTING_MODE = ThetaCapturer.ShootingMode.RIC_MOVIE_PREVIEW_1920
    }

    private var localView: SurfaceViewRenderer? = null

    private var capturer: ThetaCapturer? = null
    private var eglBase: EglBase? = null

    private var ticket: Ticket? = null

    private var channel: SoraMediaChannel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        SoraLogger.enabled = true

        setContentView(R.layout.activity_main)
        localView = findViewById(R.id.local_view)

        eglBase = EglBase.create()
        localView!!.init(eglBase!!.eglBaseContext, null)

        setupThetaDevices()
        startChannel()
    }

    private fun setupThetaDevices() {
        // Configures RICOH THETA's microphone and camera. This is not a general Android configuration.
        // see https://api.ricoh/docs/theta-plugin-reference/audio-manager-api/
        // see https://api.ricoh/docs/theta-plugin-reference/broadcast-intent/#notifying-camera-device-control
        (getSystemService(AUDIO_SERVICE) as AudioManager)
                .setParameters("RicUseBFormat=false") // recording in monaural
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
                    val track = ms.videoTracks[0]
                    track.setEnabled(true)
                    track.addSink(this@SoraMainActivity.localView)
                    capturer?.startCapture(SHOOTING_MODE.width, SHOOTING_MODE.height, 30)
                }
            }
        }

        override fun onPushMessage(mediaChannel: SoraMediaChannel, push: PushMessage) {
            Log.d(TAG, "onPushMessage: push=${push}")
            val data = push.data
            if(data is Map<*, *>) {
                data.forEach { (key, value) ->
                    Log.d(TAG, "received push data: $key=$value")
                }
            }
        }
    }

    private fun startChannel() {
        Log.d(TAG, "startChannel")

        val option = SoraMediaOption().apply {
            enableAudioUpstream()
            audioCodec = SoraAudioOption.Codec.OPUS

            enableVideoUpstream(capturer!!, eglBase!!.eglBaseContext)
            videoCodec = SoraVideoOption.Codec.VP9
            videoBitrate = 2000
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
    }

}
