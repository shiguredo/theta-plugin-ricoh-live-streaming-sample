// Copyright 2018 Ricoh Company, Ltd. All rights reserved.

package com.theta360.sample.livestreaming

import android.content.Context
import android.util.Log
import org.webrtc.*
import java.util.*
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PeerChannel(
        context: Context,
        config: PeerConnection.RTCConfiguration,
        observer: PeerConnection.Observer,
        localEglContext: EglBase.Context?
) {
    private val tag = PeerChannel::class.simpleName!!

    private val factory: PeerConnectionFactory
    private val conn: PeerConnection

    init {
        Log.d(tag, "start to initialize peer connection factory")
        val initOptions = PeerConnectionFactory.InitializationOptions
                .builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)
        Log.d(tag, "finish to initialize peer connection factory")

        Log.d(tag, "start to create peer connection factory")
        val options = PeerConnectionFactory.Options()
        val factoryBuilder = PeerConnectionFactory.builder()
                .setOptions(options)

        val encoderFactory = DefaultVideoEncoderFactory(
                localEglContext,
                true /* enableIntelVp8Encoder */,
                false /* enableH264HighProfile */)
        val decoderFactory = SoftwareVideoDecoderFactory()
//        decoderFactory.supportedCodecs.forEach {
//            SoraLogger.d(TAG, "decoderFactory supported codec: ${it.name} ${it.params}")
//        }
//        encoderFactory.supportedCodecs.forEach {
//            SoraLogger.d(TAG, "encoderFactory supported codec: ${it.name} ${it.params}")
//        }
        factoryBuilder.setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)

        factory = factoryBuilder.createPeerConnectionFactory()
        Log.d(tag, "finish to create peer connection factory")

        Log.d(tag, "start to create peer connection")
        conn = factory.createPeerConnection(config, observer)!!
        Log.d(tag, "finish to create peer connection")
    }

    fun createLocalMediaStream(): MediaStream {
        val label = UUID.randomUUID().toString()
        return factory.createLocalMediaStream(label)
    }

    fun createAudioSource(constraints: MediaConstraints): AudioSource {
        return factory.createAudioSource(constraints)
    }

    fun createAudioTrack(source: AudioSource): AudioTrack {
        val trackID = UUID.randomUUID().toString()
        return factory.createAudioTrack(trackID, source)
    }

    fun createVideoTrack(source: VideoSource): VideoTrack {
        val trackID = UUID.randomUUID().toString()
        return factory.createVideoTrack(trackID, source)
    }

    fun createVideoSource(capturer: VideoCapturer): VideoSource {
        return factory.createVideoSource(capturer.isScreencast)
    }

    fun addStream(stream: MediaStream) {
        conn.addStream(stream)
    }

    suspend fun createAnswer(constraints: MediaConstraints): SessionDescription = suspendCoroutine {
        conn.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                it.resume(sdp)
            }

            override fun onCreateFailure(s: String?) {
                it.resumeWithException(Error(s))
            }

            override fun onSetSuccess() {
                it.resumeWithException(Error("must not come here"))
            }

            override fun onSetFailure(s: String?) {
                it.resumeWithException(Error("must not come here"))
            }
        }, constraints)
    }

    suspend fun setLocalDescription(sdp: SessionDescription) = suspendCoroutine<Void?> {
        conn.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                it.resumeWithException(Error("must not come here"))
            }

            override fun onCreateFailure(s: String?) {
                it.resumeWithException(Error("must not come here"))
            }

            override fun onSetSuccess() {
                it.resume(null)
            }

            override fun onSetFailure(s: String?) {
                it.resumeWithException(Error(s))
            }
        }, sdp)
    }

    suspend fun setRemoteDescription(sdp: SessionDescription) = suspendCoroutine<Void?> {
        conn.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                it.resumeWithException(Error("must not come here"))
            }

            override fun onCreateFailure(s: String?) {
                it.resumeWithException(Error("must not come here"))
            }

            override fun onSetSuccess() {
                it.resume(null)
            }

            override fun onSetFailure(s: String?) {
                it.resumeWithException(Error(s))
            }
        }, sdp)
    }
}
