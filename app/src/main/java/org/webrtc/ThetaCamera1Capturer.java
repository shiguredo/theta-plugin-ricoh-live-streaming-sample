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

import org.webrtc.Camera1Capturer;
import org.webrtc.Camera1Enumerator;

import org.webrtc.CameraVideoCapturer;
import org.webrtc.SurfaceTextureHelper;

public class ThetaCamera1Capturer extends Camera1Capturer {
  private final boolean captureToTexture;

  public ThetaCamera1Capturer(
          String cameraName, CameraVideoCapturer.CameraEventsHandler eventsHandler, boolean captureToTexture) {
    super(cameraName, eventsHandler, captureToTexture);
    this.captureToTexture = captureToTexture;
  }

  @Override
  protected void createCameraSession(CameraSession.CreateSessionCallback createSessionCallback,
                                     CameraSession.Events events, Context applicationContext,
                                     SurfaceTextureHelper surfaceTextureHelper, String cameraName, int width, int height,
                                     int framerate) {
    ThetaCamera1Session.create(createSessionCallback, events, captureToTexture, applicationContext,
        surfaceTextureHelper, Camera1Enumerator.getCameraIndex(cameraName), width, height,
        framerate);
  }
}
