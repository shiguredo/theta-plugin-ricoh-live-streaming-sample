/*
 *  Copyright 2017 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.webrtc;

import android.annotation.TargetApi;
import android.graphics.Matrix;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.opengl.GLES20;
import android.os.Bundle;

import android.util.Log;
import android.view.Surface;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.Nullable;
import org.webrtc.ThreadUtils.ThreadChecker;

import javax.microedition.khronos.egl.EGL10;

/**
 * Android hardware video encoder.
 *
 * @note This class is only supported on Android Kitkat and above.
 */
@TargetApi(19)
@SuppressWarnings("deprecation") // Cannot support API level 19 without using deprecated methods.
class ThetaHardwareVideoEncoder implements VideoEncoder {
  private static final String TAG = "ThetaHardwareVideoEncoder";

  private final boolean verboseEncodeLog = false;
  private final boolean verboseDeliverLog = false;

  // Bitrate modes - should be in sync with OMX_VIDEO_CONTROLRATETYPE defined
  // in OMX_Video.h
  private static final int VIDEO_ControlRateConstantBitRate = 2;
  private static final int VIDEO_ControlRateConstantQuality = 0;
  private static final int VIDEO_ControlRateVariableBitRate = 1;
  // Key associated with the bitrate control mode value (above). Not present as a MediaFormat
  // constant until API level 21.
  private static final String KEY_BITRATE_MODE = "bitrate-mode";

  private static final int VIDEO_AVC_PROFILE_HIGH = 8;
  private static final int VIDEO_AVC_LEVEL_3 = 0x100;

  private static final int MAX_VIDEO_FRAMERATE = 30;

  // See MAX_ENCODER_Q_SIZE in androidmediaencoder.cc.
  private static final int MAX_ENCODER_Q_SIZE = 2;

  private static final int MEDIA_CODEC_RELEASE_TIMEOUT_MS = 5000;
  private static final int DEQUEUE_OUTPUT_BUFFER_TIMEOUT_US = 100000;

  // --- Initialized on construction.
  private final MediaCodecWrapperFactory mediaCodecWrapperFactory;
  private final String codecName;
  private final VideoCodecType codecType;
  private final Integer surfaceColorFormat;
  private final Integer yuvColorFormat;
  private final YuvFormat yuvFormat;
  private final Map<String, String> params;
  private final int keyFrameIntervalSec; // Base interval for generating key frames.
  // Interval at which to force a key frame. Used to reduce color distortions caused by some
  // Qualcomm video encoders.
  private final long forcedKeyFrameNs;
  private final BitrateAdjuster bitrateAdjuster;
  // EGL context shared with the application.  Used to access texture inputs.
  private final EglBase14.Context sharedContext;

  // Drawer used to draw input textures onto the codec's input surface.
  private final GlRectDrawer textureDrawer = new GlRectDrawer();
  private final VideoFrameDrawer videoFrameDrawer = new VideoFrameDrawer();
  // A queue of EncodedImage.Builders that correspond to frames in the codec.  These builders are
  // pre-populated with all the information that can't be sent through MediaCodec.
  private final BlockingDeque<EncodedImage.Builder> outputBuilders = new LinkedBlockingDeque<>();

  private final ThreadChecker encodeThreadChecker = new ThreadChecker();
  private final ThreadChecker outputThreadChecker = new ThreadChecker();

  // --- Set on initialize and immutable until release.
  private Callback callback;
  private boolean automaticResizeOn;

  // --- Valid and immutable while an encoding session is running.
  @Nullable private MediaCodecWrapper codecWrapper;

  // --- Valid and immutable while an encoding session is running.
  @Nullable private MediaCodec codec;
  private int outputBuffersSize = 0;

  // Thread that delivers encoded frames to the user callback.
  @Nullable private Thread outputThread;

  // EGL base wrapping the shared texture context.  Holds hooks to both the shared context and the
  // input surface.  Making this base current allows textures from the context to be drawn onto the
  // surface.
  @Nullable private EglBase14 textureEglBase;
  // Input surface for the codec.  The encoder will draw input textures onto this surface.
  @Nullable private Surface textureInputSurface;

  private int width;
  private int height;
  private boolean useSurfaceMode;

  // --- Only accessed from the encoding thread.
  // Presentation timestamp of the last requested (or forced) key frame.
  private long lastKeyFrameNs;

  // --- Only accessed on the output thread.
  // Contents of the last observed config frame output by the MediaCodec. Used by H.264.
  @Nullable private ByteBuffer configBuffer;
  private int adjustedBitrate;

  // Whether the encoder is running.  Volatile so that the output thread can watch this value and
  // exit when the encoder stops.
  private volatile boolean running;
  // Any exception thrown during shutdown.  The output thread releases the MediaCodec and uses this
  // value to send exceptions thrown during release back to the encoder thread.
  @Nullable private volatile Exception shutdownException;

  /**
   * Creates a new HardwareVideoEncoder with the given codecName, codecType, colorFormat, key frame
   * intervals, and bitrateAdjuster.
   *
   * @param codecName the hardware codec implementation to use
   * @param codecType the type of the given video codec (eg. VP8, VP9, or H264)
   * @param surfaceColorFormat color format for surface mode or null if not available
   * @param yuvColorFormat color format for bytebuffer mode
   * @param keyFrameIntervalSec interval in seconds between key frames; used to initialize the codec
   * @param forceKeyFrameIntervalMs interval at which to force a key frame if one is not requested;
   *     used to reduce distortion caused by some codec implementations
   * @param bitrateAdjuster algorithm used to correct codec implementations that do not produce the
   *     desired bitrates
   * @throws IllegalArgumentException if colorFormat is unsupported
   */
  public ThetaHardwareVideoEncoder(MediaCodecWrapperFactory mediaCodecWrapperFactory, String codecName,
      VideoCodecType codecType, Integer surfaceColorFormat, Integer yuvColorFormat,
      Map<String, String> params, int keyFrameIntervalSec, int forceKeyFrameIntervalMs,
      BitrateAdjuster bitrateAdjuster, EglBase14.Context sharedContext) {
    this.mediaCodecWrapperFactory = mediaCodecWrapperFactory;
    this.codecName = codecName;
    this.codecType = codecType;
    this.surfaceColorFormat = surfaceColorFormat;
    this.yuvColorFormat = yuvColorFormat;
    this.yuvFormat = YuvFormat.valueOf(yuvColorFormat);
    this.params = params;
    this.keyFrameIntervalSec = keyFrameIntervalSec;
    this.forcedKeyFrameNs = TimeUnit.MILLISECONDS.toNanos(forceKeyFrameIntervalMs);
    this.bitrateAdjuster = bitrateAdjuster;
    this.sharedContext = sharedContext;

    // Allow construction on a different thread.
    encodeThreadChecker.detachThread();
  }

  @Override
  public VideoCodecStatus initEncode(Settings settings, Callback callback) {
    encodeThreadChecker.checkIsOnValidThread();

    this.callback = callback;
    automaticResizeOn = settings.automaticResizeOn;
    this.width = settings.width;
    this.height = settings.height;
    useSurfaceMode = canUseSurface();

    if (settings.startBitrate != 0 && settings.maxFramerate != 0) {
      bitrateAdjuster.setTargets(settings.startBitrate * 1000, settings.maxFramerate);
    }
    adjustedBitrate = bitrateAdjuster.getAdjustedBitrateBps();

    Logging.d(TAG,
        "initEncode: " + codecName + " " + width + " x " + height + ". @ " + settings.startBitrate
            + "kbps. Fps: " + settings.maxFramerate + " Use surface mode: " + useSurfaceMode);
    return initEncodeInternal();
  }

  public static final int[] CONFIG_RECORDABLE_PIXEL_RGB_BUFFER = {
          EGL10.EGL_RED_SIZE, 8,
          EGL10.EGL_GREEN_SIZE, 8,
          EGL10.EGL_BLUE_SIZE, 8,
          EGL10.EGL_RENDERABLE_TYPE, EglBase.EGL_OPENGL_ES2_BIT,
          EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
          EglBase.EGL_RECORDABLE_ANDROID, 1,
          EGL10.EGL_NONE
  };

  public static final int[] CONFIG_RECORDABLE_RGBA_BUFFER = {
          EGL10.EGL_RED_SIZE, 8,
          EGL10.EGL_GREEN_SIZE, 8,
          EGL10.EGL_BLUE_SIZE, 8,
          EGL10.EGL_ALPHA_SIZE, 8,
          EGL10.EGL_RENDERABLE_TYPE, EglBase.EGL_OPENGL_ES2_BIT,
          EglBase.EGL_RECORDABLE_ANDROID, 1,
          EGL10.EGL_NONE
  };

  public static final int[] CONFIG_RECORDABLE_PIXEL_RGBA_BUFFER = {
          EGL10.EGL_RED_SIZE, 8,
          EGL10.EGL_GREEN_SIZE, 8,
          EGL10.EGL_BLUE_SIZE, 8,
          EGL10.EGL_ALPHA_SIZE, 8,
          EGL10.EGL_RENDERABLE_TYPE, EglBase.EGL_OPENGL_ES2_BIT,
          EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
          EglBase.EGL_RECORDABLE_ANDROID, 1,
          EGL10.EGL_NONE
  };

  private VideoCodecStatus initEncodeInternal() {
    encodeThreadChecker.checkIsOnValidThread();

    lastKeyFrameNs = -1;

    try {
      codec = MediaCodec.createByCodecName(codecName);
    } catch (IOException | IllegalArgumentException e) {
      Logging.e(TAG, "Cannot create media encoder " + codecName);
      return VideoCodecStatus.FALLBACK_SOFTWARE;
    }

    final int colorFormat = useSurfaceMode ? surfaceColorFormat : yuvColorFormat;
    try {
      MediaFormat format = MediaFormat.createVideoFormat(codecType.mimeType(), width, height);

      format.setInteger(MediaFormat.KEY_BIT_RATE, adjustedBitrate);
      // format.setInteger(MediaFormat.KEY_BIT_RATE, 10000000);

      format.setInteger(KEY_BITRATE_MODE, VIDEO_ControlRateConstantBitRate);
      // format.setInteger(KEY_BITRATE_MODE, VIDEO_ControlRateConstantQuality);
      // format.setInteger(KEY_BITRATE_MODE, VIDEO_ControlRateVariableBitRate);

      format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
      // This does not work: E/ACodec: [OMX.qcom.video.encoder.avc] does not support color format 19
      // format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
      //         MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
      // This does not work: E/ACodec: [OMX.qcom.video.encoder.avc] does not support color format 2134292616
      // format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
      //         MediaCodecInfo.CodecCapabilities.COLOR_FormatRGBFlexible);

      // bitrateAdjuster says framerate is 60, too high, yeah?
      // format.setInteger(MediaFormat.KEY_FRAME_RATE, bitrateAdjuster.getCodecConfigFramerate());
      // format.setFloat(MediaFormat.KEY_FRAME_RATE, 29.97f);
      format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);

      format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameIntervalSec);
      // format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 90);

      if (codecType == VideoCodecType.H264) {
        String profileLevelId = params.get(VideoCodecInfo.H264_FMTP_PROFILE_LEVEL_ID);
        if (profileLevelId == null) {
          profileLevelId = VideoCodecInfo.H264_CONSTRAINED_BASELINE_3_1;
        }
        switch (profileLevelId) {
          case VideoCodecInfo.H264_CONSTRAINED_HIGH_3_1:
            format.setInteger("profile", VIDEO_AVC_PROFILE_HIGH);
            format.setInteger("level", VIDEO_AVC_LEVEL_3);
            break;
          case VideoCodecInfo.H264_CONSTRAINED_BASELINE_3_1:
            break;
          default:
            Logging.w(TAG, "Unknown profile level id: " + profileLevelId);
        }
      }
      Logging.d(TAG, "Format: " + format);

      codec.configure(
          format, null /* surface */, null /* crypto */, MediaCodec.CONFIGURE_FLAG_ENCODE);

      if (useSurfaceMode) {
        textureEglBase = new EglBase14(sharedContext, EglBase.CONFIG_RECORDABLE);
        // textureEglBase = new EglBase14(sharedContext, CONFIG_RECORDABLE_PIXEL_RGBA_BUFFER);
        // textureEglBase = new EglBase14(sharedContext, CONFIG_RECORDABLE_RGBA_BUFFER);
        textureInputSurface = codec.createInputSurface();
        textureEglBase.createSurface(textureInputSurface);
        textureEglBase.makeCurrent();
      }

      codec.start();
    } catch (IllegalStateException e) {
      Logging.e(TAG, "initEncodeInternal failed", e);
      release();
      return VideoCodecStatus.FALLBACK_SOFTWARE;
    }

    running = true;
    outputThreadChecker.detachThread();
    outputThread = createOutputThread();
    outputThread.start();

    return VideoCodecStatus.OK;
  }

  @Override
  public VideoCodecStatus release() {
    encodeThreadChecker.checkIsOnValidThread();

    final VideoCodecStatus returnValue;
    if (outputThread == null) {
      returnValue = VideoCodecStatus.OK;
    } else {
      // The outputThread actually stops and releases the codec once running is false.
      running = false;
      if (!ThreadUtils.joinUninterruptibly(outputThread, MEDIA_CODEC_RELEASE_TIMEOUT_MS)) {
        Logging.e(TAG, "Media encoder release timeout");
        returnValue = VideoCodecStatus.TIMEOUT;
      } else if (shutdownException != null) {
        // Log the exception and turn it into an error.
        Logging.e(TAG, "Media encoder release exception", shutdownException);
        returnValue = VideoCodecStatus.ERROR;
      } else {
        returnValue = VideoCodecStatus.OK;
      }
    }

    textureDrawer.release();
    videoFrameDrawer.release();
    if (textureEglBase != null) {
      textureEglBase.release();
      textureEglBase = null;
    }
    if (textureInputSurface != null) {
      textureInputSurface.release();
      textureInputSurface = null;
    }
    outputBuilders.clear();

    codec = null;
    outputThread = null;

    // Allow changing thread after release.
    encodeThreadChecker.detachThread();

    return returnValue;
  }

  @Override
  public VideoCodecStatus encode(VideoFrame videoFrame, EncodeInfo encodeInfo) {
    encodeThreadChecker.checkIsOnValidThread();
    if (codec == null) {
      return VideoCodecStatus.UNINITIALIZED;
    }

    final VideoFrame.Buffer videoFrameBuffer = videoFrame.getBuffer();
    final boolean isTextureBuffer = videoFrameBuffer instanceof VideoFrame.TextureBuffer;

    // If input resolution changed, restart the codec with the new resolution.
    final int frameWidth = videoFrame.getBuffer().getWidth();
    final int frameHeight = videoFrame.getBuffer().getHeight();
    final boolean shouldUseSurfaceMode = canUseSurface() && isTextureBuffer;

    if (frameWidth != width || frameHeight != height || shouldUseSurfaceMode != useSurfaceMode) {
      VideoCodecStatus status = resetCodec(frameWidth, frameHeight, shouldUseSurfaceMode);
      if (status != VideoCodecStatus.OK) {
        return status;
      }
    }

    if (outputBuilders.size() > MAX_ENCODER_Q_SIZE) {
      // Too many frames in the encoder.  Drop this frame.
      Logging.e(TAG, "Dropped frame, encoder queue full");
      return VideoCodecStatus.NO_OUTPUT; // See webrtc bug 2887.
    }

    boolean requestedKeyFrame = false;
    for (EncodedImage.FrameType frameType : encodeInfo.frameTypes) {
      if (frameType == EncodedImage.FrameType.VideoFrameKey) {
        requestedKeyFrame = true;
      }
    }

    if (requestedKeyFrame || shouldForceKeyFrame(videoFrame.getTimestampNs())) {
      requestKeyFrame(videoFrame.getTimestampNs());
    }

    // Number of bytes in the video buffer. Y channel is sampled at one byte per pixel; U and V are
    // subsampled at one byte per four pixels.
    int bufferSize = videoFrameBuffer.getHeight() * videoFrameBuffer.getWidth() * 3 / 2;
    EncodedImage.Builder builder = EncodedImage.builder()
                                       .setCaptureTimeNs(videoFrame.getTimestampNs())
                                       .setCompleteFrame(true)
                                       .setEncodedWidth(videoFrame.getBuffer().getWidth())
                                       .setEncodedHeight(videoFrame.getBuffer().getHeight())
                                       .setRotation(videoFrame.getRotation());
    outputBuilders.offer(builder);

    final VideoCodecStatus returnValue;
    if (useSurfaceMode) {
      returnValue = encodeTextureBuffer(videoFrame);
    } else {
      returnValue = encodeByteBuffer(videoFrame, videoFrameBuffer, bufferSize);
    }

    // Check if the queue was successful.
    if (returnValue != VideoCodecStatus.OK) {
      // Keep the output builders in sync with buffers in the codec.
      outputBuilders.pollLast();
    }

    return returnValue;
  }

  private VideoCodecStatus encodeTextureBuffer(VideoFrame videoFrame) {
    encodeThreadChecker.checkIsOnValidThread();
    long start = 0;
    long mid = 0;
    if (verboseEncodeLog) {
      start = System.currentTimeMillis();
    }
    try {
      // TODO(perkj): glClear() shouldn't be necessary since every pixel is covered anyway,
      // but it's a workaround for bug webrtc:5147.
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
      // It is not necessary to release this frame because it doesn't own the buffer.
      VideoFrame derotatedFrame =
          new VideoFrame(videoFrame.getBuffer(), 0 /* rotation */, videoFrame.getTimestampNs());
      videoFrameDrawer.drawFrame(derotatedFrame, textureDrawer, null /* additionalRenderMatrix */);
      if(verboseEncodeLog) {
        mid = System.currentTimeMillis();
        Logging.d(TAG, "encodeTextureBuffer: to drawFrame finished = " + (mid - start));
      }
      textureEglBase.swapBuffers(videoFrame.getTimestampNs());
    } catch (RuntimeException e) {
      Logging.e(TAG, "encodeTexture failed", e);
      return VideoCodecStatus.ERROR;
    }
    if(verboseEncodeLog) {
      Logging.d(TAG, "encodeTextureBuffer: to swapBuffers finished = " + (System.currentTimeMillis() - mid));
    }
    return VideoCodecStatus.OK;
  }

  private VideoCodecStatus encodeByteBuffer(
      VideoFrame videoFrame, VideoFrame.Buffer videoFrameBuffer, int bufferSize) {
    encodeThreadChecker.checkIsOnValidThread();
    // Frame timestamp rounded to the nearest microsecond.
    long presentationTimestampUs = (videoFrame.getTimestampNs() + 500) / 1000;

    // No timeout.  Don't block for an input buffer, drop frames if the encoder falls behind.
    int index;
    try {
      index = codec.dequeueInputBuffer(0 /* timeout */);
    } catch (IllegalStateException e) {
      Logging.e(TAG, "dequeueInputBuffer failed", e);
      return VideoCodecStatus.ERROR;
    }

    if (index == -1) {
      // Encoder is falling behind.  No input buffers available.  Drop the frame.
      Logging.d(TAG, "Dropped frame, no input buffers available");
      return VideoCodecStatus.NO_OUTPUT; // See webrtc bug 2887.
    }

    ByteBuffer buffer;
    try {
      buffer = codec.getInputBuffers()[index];
    } catch (IllegalStateException e) {
      Logging.e(TAG, "getInputBuffers failed", e);
      return VideoCodecStatus.ERROR;
    }
    fillInputBuffer(buffer, videoFrameBuffer);

    try {
      codec.queueInputBuffer(
          index, 0 /* offset */, bufferSize, presentationTimestampUs, 0 /* flags */);
    } catch (IllegalStateException e) {
      Logging.e(TAG, "queueInputBuffer failed", e);
      // IllegalStateException thrown when the codec is in the wrong state.
      return VideoCodecStatus.ERROR;
    }
    return VideoCodecStatus.OK;
  }

  @Override
  public VideoCodecStatus setRateAllocation(BitrateAllocation bitrateAllocation, int framerate) {
    encodeThreadChecker.checkIsOnValidThread();
    if (framerate > MAX_VIDEO_FRAMERATE) {
      framerate = MAX_VIDEO_FRAMERATE;
    }
    bitrateAdjuster.setTargets(bitrateAllocation.getSum(), framerate);
    return VideoCodecStatus.OK;
  }

  @Override
  public ScalingSettings getScalingSettings() {
    encodeThreadChecker.checkIsOnValidThread();
    if (automaticResizeOn) {
      if (codecType == VideoCodecType.VP8) {
        final int kLowVp8QpThreshold = 29;
        final int kHighVp8QpThreshold = 95;
        return new ScalingSettings(kLowVp8QpThreshold, kHighVp8QpThreshold);
      } else if (codecType == VideoCodecType.H264) {
        final int kLowH264QpThreshold = 24;
        final int kHighH264QpThreshold = 37;
        return new ScalingSettings(kLowH264QpThreshold, kHighH264QpThreshold);
      }
    }
    return ScalingSettings.OFF;
  }

  @Override
  public String getImplementationName() {
    return "HWEncoder";
  }

  private VideoCodecStatus resetCodec(int newWidth, int newHeight, boolean newUseSurfaceMode) {
    encodeThreadChecker.checkIsOnValidThread();
    Logging.d(TAG, "resetCodec: " + newWidth + "x" + newHeight +
            ", newUseSurfaceMode=" + newUseSurfaceMode);

    VideoCodecStatus status = release();
    if (status != VideoCodecStatus.OK) {
      return status;
    }
    width = newWidth;
    height = newHeight;
    useSurfaceMode = newUseSurfaceMode;
    return initEncodeInternal();
  }

  private boolean shouldForceKeyFrame(long presentationTimestampNs) {
    encodeThreadChecker.checkIsOnValidThread();
    return forcedKeyFrameNs > 0 && presentationTimestampNs > lastKeyFrameNs + forcedKeyFrameNs;
  }

  private void requestKeyFrame(long presentationTimestampNs) {
    encodeThreadChecker.checkIsOnValidThread();
    // Ideally MediaCodec would honor BUFFER_FLAG_SYNC_FRAME so we could
    // indicate this in queueInputBuffer() below and guarantee _this_ frame
    // be encoded as a key frame, but sadly that flag is ignored.  Instead,
    // we request a key frame "soon".
    try {
      Bundle b = new Bundle();
      b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
      codec.setParameters(b);
    } catch (IllegalStateException e) {
      Logging.e(TAG, "requestKeyFrame failed", e);
      return;
    }
    lastKeyFrameNs = presentationTimestampNs;
  }

  private Thread createOutputThread() {
    return new Thread() {
      @Override
      public void run() {
        while (running) {
          deliverEncodedImage();
        }
        releaseCodecOnOutputThread();
      }
    };
  }

  // Visible for testing.
  protected void deliverEncodedImage() {
    outputThreadChecker.checkIsOnValidThread();
    long start = 0;
    long dequeued = 0;
    long beforeOnEncodedFrame = 0;
    long afterOnEncodedFrame = 0;

    if (verboseDeliverLog) {
      System.currentTimeMillis();
    }
    try {
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      long bufferInfo = System.currentTimeMillis();
      if(verboseDeliverLog) Logging.d(TAG, "deliverEncodedImage: start -> bufferInfo = " + (bufferInfo - start));
      int index = codec.dequeueOutputBuffer(info, DEQUEUE_OUTPUT_BUFFER_TIMEOUT_US);
      if (verboseDeliverLog) {
        dequeued = System.currentTimeMillis();
        Logging.d(TAG, "deliverEncodedImage: bufferInfo -> dequeued = " + (dequeued - bufferInfo));
      }
      if (index < 0) {
        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
          Logging.d(TAG, "deliverEncodedImage: negative index=" + index + " (INFO_TRY_AGAIN_LATER)");
        } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          Logging.d(TAG, "deliverEncodedImage: negative index=" + index + " (INFO_OUTPUT_FORMAT_CHANGED)");
        } else if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
          Logging.d(TAG, "deliverEncodedImage: negative index=" + index + " (INFO_OUTPUT_BUFFERS_CHANGED)");
        } else {
          Logging.d(TAG, "deliverEncodedImage: negative index=" + index + " (SHOULD NEVER HAPPEN)");
        }
        return;
      }
      if(outputBuffersSize == 0) {
        // outputBuffersSize = codec.getOutputBuffers().length;
        outputBuffersSize = 4; // hard coded
      }
      // Logging.d(TAG, "codecOutputBuffers index/length=" + index + "/" + outputBuffersSize);
      ByteBuffer codecOutputBuffer = codec.getOutputBuffer(index);
      codecOutputBuffer.position(info.offset);
      codecOutputBuffer.limit(info.offset + info.size);

      if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
        if(verboseDeliverLog) Logging.d(TAG, "Config frame generated. Offset: " + info.offset + ". Size: " + info.size);
        configBuffer = ByteBuffer.allocateDirect(info.size);
        configBuffer.put(codecOutputBuffer);
      } else {
        bitrateAdjuster.reportEncodedFrame(info.size);
        if (adjustedBitrate != bitrateAdjuster.getAdjustedBitrateBps()) {
          updateBitrate();
        }

        final boolean isKeyFrame = (info.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0;
        if (isKeyFrame) {
          Logging.d(TAG, "Sync frame generated");
        }

        final ByteBuffer frameBuffer;
        if (isKeyFrame && codecType == VideoCodecType.H264) {
          Logging.d(TAG,
              "Prepending config frame of size " + configBuffer.capacity()
                  + " to output buffer with offset " + info.offset + ", size " + info.size);
          // For H.264 key frame prepend SPS and PPS NALs at the start.
          frameBuffer = ByteBuffer.allocateDirect(info.size + configBuffer.capacity());
          configBuffer.rewind();
          frameBuffer.put(configBuffer);
          frameBuffer.put(codecOutputBuffer);
          frameBuffer.rewind();
        } else {
          frameBuffer = codecOutputBuffer.slice();
        }

        final EncodedImage.FrameType frameType = isKeyFrame
            ? EncodedImage.FrameType.VideoFrameKey
            : EncodedImage.FrameType.VideoFrameDelta;

        EncodedImage.Builder builder = outputBuilders.poll();
        builder.setBuffer(frameBuffer).setFrameType(frameType);
        // TODO(mellem):  Set codec-specific info.
        if(verboseDeliverLog) {
          beforeOnEncodedFrame = System.currentTimeMillis();
          Logging.d(TAG, "deliverEncodedImage: dequeued -> beforeOnEncodedFrame = " + (beforeOnEncodedFrame - dequeued));

        }
        callback.onEncodedFrame(builder.createEncodedImage(), new CodecSpecificInfo());
        if(verboseDeliverLog) {
          afterOnEncodedFrame = System.currentTimeMillis();
          Logging.d(TAG, "deliverEncodedImage: beforeOnEncodedFrame -> afterOnEncodedFrame = "
                  + (afterOnEncodedFrame - beforeOnEncodedFrame));
        }
      }
      codec.releaseOutputBuffer(index, false);
      if(verboseDeliverLog) {
        long afterRelease = System.currentTimeMillis();
        Logging.d(TAG, "deliverEncodedImage: afterOnEncodedFrame -> afterRelease = "
                + (afterRelease - afterOnEncodedFrame));
      }
    } catch (IllegalStateException e) {
      Logging.e(TAG, "deliverOutput failed", e);
    }
  }

  private void releaseCodecOnOutputThread() {
    outputThreadChecker.checkIsOnValidThread();
    Logging.d(TAG, "Releasing MediaCodec on output thread");
    try {
      codec.stop();
    } catch (Exception e) {
      Logging.e(TAG, "Media encoder stop failed", e);
    }
    try {
      codec.release();
    } catch (Exception e) {
      Logging.e(TAG, "Media encoder release failed", e);
      // Propagate exceptions caught during release back to the main thread.
      shutdownException = e;
    }
    configBuffer = null;
    Logging.d(TAG, "Release on output thread done");
  }

  private VideoCodecStatus updateBitrate() {
    outputThreadChecker.checkIsOnValidThread();
    adjustedBitrate = bitrateAdjuster.getAdjustedBitrateBps();
    try {
      Bundle params = new Bundle();
      params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, adjustedBitrate);
      codec.setParameters(params);
      return VideoCodecStatus.OK;
    } catch (IllegalStateException e) {
      Logging.e(TAG, "updateBitrate failed", e);
      return VideoCodecStatus.ERROR;
    }
  }

  private boolean canUseSurface() {
    return sharedContext != null && surfaceColorFormat != null;
  }

  // Visible for testing.
  protected void fillInputBuffer(ByteBuffer buffer, VideoFrame.Buffer videoFrameBuffer) {
    yuvFormat.fillBuffer(buffer, videoFrameBuffer);
  }

  /**
   * Enumeration of supported YUV color formats used for MediaCodec's input.
   */
  private enum YuvFormat {
    I420 {
      @Override
      void fillBuffer(ByteBuffer dstBuffer, VideoFrame.Buffer srcBuffer) {
        VideoFrame.I420Buffer i420 = srcBuffer.toI420();
        YuvHelper.I420Copy(i420.getDataY(), i420.getStrideY(), i420.getDataU(), i420.getStrideU(),
            i420.getDataV(), i420.getStrideV(), dstBuffer, i420.getWidth(), i420.getHeight());
        i420.release();
      }
    },
    NV12 {
      @Override
      void fillBuffer(ByteBuffer dstBuffer, VideoFrame.Buffer srcBuffer) {
        VideoFrame.I420Buffer i420 = srcBuffer.toI420();
        YuvHelper.I420ToNV12(i420.getDataY(), i420.getStrideY(), i420.getDataU(), i420.getStrideU(),
            i420.getDataV(), i420.getStrideV(), dstBuffer, i420.getWidth(), i420.getHeight());
        i420.release();
      }
    };

    abstract void fillBuffer(ByteBuffer dstBuffer, VideoFrame.Buffer srcBuffer);

    static YuvFormat valueOf(int colorFormat) {
      switch (colorFormat) {
        case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
          return I420;
        case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
        case MediaCodecInfo.CodecCapabilities.COLOR_QCOM_FormatYUV420SemiPlanar:
        case MediaCodecUtils.COLOR_QCOM_FORMATYUV420PackedSemiPlanar32m:
          return NV12;
        default:
          throw new IllegalArgumentException("Unsupported colorFormat: " + colorFormat);
      }
    }
  }
}
