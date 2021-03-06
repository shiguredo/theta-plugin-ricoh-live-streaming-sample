/*
 *  Copyright 2016 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.webrtc;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;

import com.theta360.sample.livestreaming.ShootingMode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.webrtc.CameraEnumerationAndroid.CaptureFormat;

@SuppressWarnings("deprecation")
class ThetaCamera1Session implements CameraSession {
  private static final String TAG = "Camera1Session";
  private static final int NUMBER_OF_CAPTURE_BUFFERS = 3;

  private static final Histogram camera1StartTimeMsHistogram =
      Histogram.createCounts("WebRTC.Android.Camera1.StartTimeMs", 1, 10000, 50);
  private static final Histogram camera1StopTimeMsHistogram =
      Histogram.createCounts("WebRTC.Android.Camera1.StopTimeMs", 1, 10000, 50);
  private static final Histogram camera1ResolutionHistogram = Histogram.createEnumeration(
      "WebRTC.Android.Camera1.Resolution", CameraEnumerationAndroid.COMMON_RESOLUTIONS.size());

  private static enum SessionState { RUNNING, STOPPED }

  private ShootingMode shootingMode;

  private final Handler cameraThreadHandler;
  private final Events events;
  private final boolean captureToTexture;
  private final Context applicationContext;
  private final SurfaceTextureHelper surfaceTextureHelper;
  private final int cameraId;
  private final android.hardware.Camera camera;
  private final android.hardware.Camera.CameraInfo info;
  private final CaptureFormat captureFormat;
  // Used only for stats. Only used on the camera thread.
  private final long constructionTimeNs; // Construction time of this class.

  private SessionState state;
  private boolean firstFrameReported;

  // TODO(titovartem) make correct fix during webrtc:9175
  @SuppressWarnings("ByteBufferBackingArray")
  public static void create(ShootingMode shootingMode,
                            final CreateSessionCallback callback, final Events events,
                            final boolean captureToTexture, final Context applicationContext,
                            final SurfaceTextureHelper surfaceTextureHelper, final int cameraId, final int width,
                            final int height, final int frameRate) {
    final long constructionTimeNs = System.nanoTime();
    Logging.d(TAG, "Open camera " + cameraId);
    events.onCameraOpening();

    final android.hardware.Camera camera;
    try {
      camera = android.hardware.Camera.open(cameraId);
    } catch (RuntimeException e) {
      callback.onFailure(FailureType.ERROR, e.getMessage());
      return;
    }

    if (camera == null) {
      callback.onFailure(FailureType.ERROR,
          "android.hardware.Camera.open returned null for camera id = " + cameraId);
      return;
    }

    try {
      camera.setPreviewTexture(surfaceTextureHelper.getSurfaceTexture());
    } catch (IOException | RuntimeException e) {
      camera.release();
      callback.onFailure(FailureType.ERROR, e.getMessage());
      return;
    }

    final android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
    android.hardware.Camera.getCameraInfo(cameraId, info);

    final CaptureFormat captureFormat;
    try {
        final android.hardware.Camera.Parameters parameters = camera.getParameters();
        CaptureFormat.FramerateRange frameRateRange = new CaptureFormat.FramerateRange(frameRate, frameRate);
        captureFormat = new CaptureFormat(width, height, frameRateRange);
        final Size pictureSize = findClosestPictureSize(parameters, width, height);
        updateCameraParameters(camera, parameters, shootingMode, frameRate, captureToTexture,
                captureFormat, pictureSize);
    } catch (RuntimeException e) {
        camera.release();
        callback.onFailure(FailureType.ERROR, e.getMessage());
        return;
    }

    if (!captureToTexture) {
        final int frameSize = captureFormat.frameSize();
        for (int i = 0; i < NUMBER_OF_CAPTURE_BUFFERS; ++i) {
            final ByteBuffer buffer = ByteBuffer.allocateDirect(frameSize);
            camera.addCallbackBuffer(buffer.array());
       }
    }

    // Calculate orientation manually and send it as CVO insted.
    // camera.setDisplayOrientation(0 /* degrees */);

    callback.onDone(new ThetaCamera1Session(events, captureToTexture, applicationContext,
        surfaceTextureHelper, cameraId, camera, info, captureFormat, constructionTimeNs));
  }

  private static void updateCameraParameters(android.hardware.Camera camera,
                                             android.hardware.Camera.Parameters parameters,
                                             ShootingMode shootingMode,
                                             int frameRate,
                                             boolean captureToTexture,
                                             CaptureFormat captureFormat,
                                             Size pictureSize) {
//      for(Camera.Size size : parameters.getSupportedPreviewSizes()) {
//          Log.d(TAG, "supported preview sizes: " + size.width + "x" + size.height);
//      }
//      for(Camera.Size size : parameters.getSupportedVideoSizes()) {
//          Log.d(TAG, "supported video sizes: " + size.width + "x" + size.height);
//      }

      // Sometimes, maybe just after restart?, camera emits no preview with RicMovieRecording4kEqui.
      // Once set to RicMoviePreview3840 here. It will be overwritten afterward.
      parameters.set("RIC_SHOOTING_MODE",
              ShootingMode.RIC_MOVIE_PREVIEW_3840.getValue());
      camera.setParameters(parameters);

      parameters.setPreviewFrameRate(frameRate);
      // This does NOT work.
      // parameters.setPreviewFpsRange(frameRate, frameRate);

      parameters.set("video-size", shootingMode.getVideoSize());

      parameters.set("RIC_SHOOTING_MODE", shootingMode.getValue());
      if (shootingMode.getEqui()) {
        parameters.set("RIC_PROC_STITCHING", "RicStaticStitching");
      } else {
        parameters.set("RIC_PROC_STITCHING", "RicNonStitching");
      }
      // If recording-hint is set to true, camera become frozen.
      // parameters.set("recording-hint", "false");
      // It seems the same as "recording-hint" above. Do not set this true.
      // parameters.setRecordingHint(true);

      // parameters.set("RIC_PROC_STITCHING", "RicNonStitching");
      // parameters.set("RIC_PROC_STITCHING", "RicStaticStitching");
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

      parameters.setPreviewSize(shootingMode.getWidth(), shootingMode.getHeight());
      // What are these numbers?
      // parameters.setPreviewSize(5376, 2688);

      // No need for this? I guess only preview is used.
      // Almost marginal but maybe slightly better FPS when set.
      // parameters.setPictureSize(pictureSize.width, pictureSize.height);
      // parameters.setPictureSize(5376, 2688);

      if (!captureToTexture) {
          parameters.setPreviewFormat(captureFormat.imageFormat);
      }

      if (parameters.isVideoStabilizationSupported()) {
          parameters.setVideoStabilization(true);
      }
      // if (focusModes.contains(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
      //     parameters.setFocusMode(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
      // }
      camera.setParameters(parameters);

      // camera.unlock();
  }

  private static CaptureFormat findClosestCaptureFormat(
      android.hardware.Camera.Parameters parameters, int width, int height, int framerate) {
    // Find closest supported format for |width| x |height| @ |framerate|.
    final List<CaptureFormat.FramerateRange> supportedFramerates =
        Camera1Enumerator.convertFramerates(parameters.getSupportedPreviewFpsRange());
    Logging.d(TAG, "Available fps ranges: " + supportedFramerates);

    final CaptureFormat.FramerateRange fpsRange =
        CameraEnumerationAndroid.getClosestSupportedFramerateRange(supportedFramerates, framerate);

    final Size previewSize = CameraEnumerationAndroid.getClosestSupportedSize(
        Camera1Enumerator.convertSizes(parameters.getSupportedPreviewSizes()), width, height);
    CameraEnumerationAndroid.reportCameraResolution(camera1ResolutionHistogram, previewSize);

    return new CaptureFormat(previewSize.width, previewSize.height, fpsRange);
  }

  private static Size findClosestPictureSize(
      android.hardware.Camera.Parameters parameters, int width, int height) {
    return CameraEnumerationAndroid.getClosestSupportedSize(
        Camera1Enumerator.convertSizes(parameters.getSupportedPictureSizes()), width, height);
  }

  private ThetaCamera1Session(Events events, boolean captureToTexture, Context applicationContext,
      SurfaceTextureHelper surfaceTextureHelper, int cameraId, android.hardware.Camera camera,
      android.hardware.Camera.CameraInfo info, CaptureFormat captureFormat,
      long constructionTimeNs) {
    Logging.d(TAG, "Create new camera1 session on camera " + cameraId);

    this.cameraThreadHandler = new Handler();
    this.events = events;
    this.captureToTexture = captureToTexture;
    this.applicationContext = applicationContext;
    this.surfaceTextureHelper = surfaceTextureHelper;
    this.cameraId = cameraId;
    this.camera = camera;
    this.info = info;
    this.captureFormat = captureFormat;
    this.constructionTimeNs = constructionTimeNs;

    surfaceTextureHelper.setTextureSize(captureFormat.width, captureFormat.height);

    startCapturing();
  }

  @Override
  public void stop() {
    Logging.d(TAG, "Stop camera1 session on camera " + cameraId);
    checkIsOnCameraThread();
    if (state != SessionState.STOPPED) {
      final long stopStartTime = System.nanoTime();
      stopInternal();
      final int stopTimeMs = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStartTime);
      camera1StopTimeMsHistogram.addSample(stopTimeMs);
    }
  }

  private void startCapturing() {
    Logging.d(TAG, "Start capturing");
    checkIsOnCameraThread();

    state = SessionState.RUNNING;

    camera.setErrorCallback(new android.hardware.Camera.ErrorCallback() {
      @Override
      public void onError(int error, android.hardware.Camera camera) {
        String errorMessage;
        if (error == android.hardware.Camera.CAMERA_ERROR_SERVER_DIED) {
          errorMessage = "Camera server died!";
        } else {
          errorMessage = "Camera error: " + error;
        }
        Logging.e(TAG, errorMessage);
        stopInternal();
        if (error == android.hardware.Camera.CAMERA_ERROR_EVICTED) {
          events.onCameraDisconnected(ThetaCamera1Session.this);
        } else {
          events.onCameraError(ThetaCamera1Session.this, errorMessage);
        }
      }
    });

    if (captureToTexture) {
      listenForTextureFrames();
    } else {
      listenForBytebufferFrames();
    }
    try {
      camera.startPreview();
    } catch (RuntimeException e) {
      stopInternal();
      events.onCameraError(this, e.getMessage());
    }
  }

  private void stopInternal() {
    Logging.d(TAG, "Stop internal");
    checkIsOnCameraThread();
    if (state == SessionState.STOPPED) {
      Logging.d(TAG, "Camera is already stopped");
      return;
    }

    state = SessionState.STOPPED;
    surfaceTextureHelper.stopListening();
    // Note: stopPreview or other driver code might deadlock. Deadlock in
    // android.hardware.Camera._stopPreview(Native Method) has been observed on
    // Nexus 5 (hammerhead), OS version LMY48I.
    camera.stopPreview();
    camera.release();
    events.onCameraClosed(this);
    Logging.d(TAG, "Stop done");
  }

  private void listenForTextureFrames() {
    surfaceTextureHelper.startListening((VideoFrame frame) -> {
      checkIsOnCameraThread();

      if (state != SessionState.RUNNING) {
        Logging.d(TAG, "Texture frame captured but camera is no longer running.");
        return;
      }

      if (!firstFrameReported) {
        final int startTimeMs =
            (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - constructionTimeNs);
        camera1StartTimeMsHistogram.addSample(startTimeMs);
        firstFrameReported = true;
      }

//      Logging.d(TAG, "texutre frame buffer : " + frame.getBuffer().getWidth() + "x"
//              + frame.getBuffer().getHeight() + " of " + frame.getBuffer().getClass().getName());
//      TextureBufferImpl bufferImpl = (TextureBufferImpl) frame.getBuffer();
//      Logging.d(TAG, "TextureBufferImpl type=" + bufferImpl.getType() + ", unscaled="
//                      + bufferImpl.getUnscaledWidth() + "x" + bufferImpl.getUnscaledHeight());

      // Undo the mirror that the OS "helps" us with.
      // http://developer.android.com/reference/android/hardware/Camera.html#setDisplayOrientation(int)
//      final VideoFrame modifiedFrame = new VideoFrame(
//          CameraSession.createTextureBufferWithModifiedTransformMatrix(
//              (TextureBufferImpl) frame.getBuffer(),
//              /* mirror= */ info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT,
//              /* rotation= */ 0),
//          /* rotation= */ getFrameOrientation(), frame.getTimestampNs());
//      events.onFrameCaptured(ThetaCamera1Session.this, modifiedFrame);
//      modifiedFrame.release();

      events.onFrameCaptured(ThetaCamera1Session.this, frame);
    });
  }

  private void listenForBytebufferFrames() {
    camera.setPreviewCallbackWithBuffer(new android.hardware.Camera.PreviewCallback() {
      @Override
      public void onPreviewFrame(final byte[] data, android.hardware.Camera callbackCamera) {
        checkIsOnCameraThread();

        if (callbackCamera != camera) {
          Logging.e(TAG, "Callback from a different camera. This should never happen.");
          return;
        }

        if (state != SessionState.RUNNING) {
          Logging.d(TAG, "Bytebuffer frame captured but camera is no longer running.");
          return;
        }

        final long captureTimeNs = TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime());

        if (!firstFrameReported) {
          final int startTimeMs =
              (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - constructionTimeNs);
          camera1StartTimeMsHistogram.addSample(startTimeMs);
          firstFrameReported = true;
        }

        VideoFrame.Buffer frameBuffer = new NV21Buffer(
            data, captureFormat.width, captureFormat.height, () -> cameraThreadHandler.post(() -> {
              if (state == SessionState.RUNNING) {
                camera.addCallbackBuffer(data);
              }
            }));
        final VideoFrame frame = new VideoFrame(frameBuffer, getFrameOrientation(), captureTimeNs);
        events.onFrameCaptured(ThetaCamera1Session.this, frame);
        frame.release();
      }
    });
  }

  private int getFrameOrientation() {
    int rotation = CameraSession.getDeviceOrientation(applicationContext);
    if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK) {
      rotation = 360 - rotation;
    }
    return (info.orientation + rotation) % 360;
  }

  private void checkIsOnCameraThread() {
    if (Thread.currentThread() != cameraThreadHandler.getLooper().getThread()) {
      throw new IllegalStateException("Wrong thread");
    }
  }
}
