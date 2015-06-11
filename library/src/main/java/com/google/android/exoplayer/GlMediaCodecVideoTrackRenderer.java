/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer;

import com.google.android.exoplayer.GlUtil.UnsupportedEglVersionException;
import com.google.android.exoplayer.drm.DrmSessionManager;

import android.annotation.TargetApi;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Handler;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * {@link MediaCodecVideoTrackRenderer} that renders to an off-screen surface rather than rendering
 * directly to the surface passed with {@link MediaCodecVideoTrackRenderer#MSG_SET_SURFACE}. This
 * allows the app to keep the same configured MediaCodec and copy the output to whatever displayed
 * surface is shown at any time.
 *
 * <p>To copy the pixel data from the decoder's output texture to the current output surface, the
 * renderer associates a GLES context with the playback thread and uses a shader for copying from
 * the off-screen surface to the current output surface.
 */
@TargetApi(17)
public class GlMediaCodecVideoTrackRenderer extends MediaCodecVideoTrackRenderer {

  private GlResources glResources;
  private SurfaceTexture decoderSurfaceTexture;
  private int decoderTexId;

  /**
   * Used in conjunction with {@link #isDecoderSurfacePopulated} to track the rendering state. The
   * state transitions are:
   * <p>
   * waitingForRender = false, isDecoderSurfacePopulated = false: continue decoding
   * <p>
   * waitingForRender = true, isDecoderSurfacePopulated = false: MediaCodec.releaseOutputBuffer was
   * called (with the render parameter set to true) in the superclass, so we are now waiting for
   * the decoder output surface texture to be updated with the decoded frame.
   * <p>
   * waitingForRender = true, isDecoderSurfacePopulated = true: the onFrameAvailable was called (on
   * a different thread), so we can now call SurfaceTexture.updateTexImage. After it returns, we can
   * access the frame in a GLES shader via the decoderTexId texture.
   * <p>
   * waitingForRender = false, isDecoderSurfacePopulated = true: run the shader program to copy to
   * the output texture, and, after it runs, return to the first state.
   */
  private boolean waitingForRender;
  private volatile boolean isDecoderSurfacePopulated;

  private int copyExternalProgram;
  private GlUtil.Attribute[] copyExternalAttributes;
  private GlUtil.Uniform[] copyExternalUniforms;

  private Surface outputSurface;
  private int outputSurfaceWidth;
  private int outputSurfaceHeight;
  private EGLSurface outputEglSurface;

  public GlMediaCodecVideoTrackRenderer(SampleSource source, DrmSessionManager drmSessionManager,
      boolean playClearSamplesWithoutKeys, int videoScalingMode, long allowedJoiningTimeMs,
      FrameReleaseTimeHelper frameReleaseTimeHelper, Handler eventHandler,
      EventListener eventListener, int maxDroppedFrameCountToNotify) {
    super(source, drmSessionManager, playClearSamplesWithoutKeys, videoScalingMode,
        allowedJoiningTimeMs, frameReleaseTimeHelper, eventHandler, eventListener,
        maxDroppedFrameCountToNotify);
  }

  @Override
  public void handleMessage(int messageType, Object message) throws ExoPlaybackException {
    if (messageType == MSG_SET_SURFACE) {
      // One time initialization for copying from the decoder to a surface using OpenGL.
      if (glResources == null) {
        initializeRendering();
      }

      // Ignore setting the same surface more than once.
      Surface newSurface = (Surface) message;
      if (newSurface == outputSurface) {
        return;
      }

      outputSurface = newSurface;
      outputEglSurface = EGL14.EGL_NO_SURFACE;
    } else {
      super.handleMessage(messageType, message);
    }
  }

  @Override
  protected void doSomeWork(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
    // Don't try to render if there is no output surface.
    if (outputSurface == null || !outputSurface.isValid()) {
      return;
    }

    // Create an EGL surface for the new outputSurface.
    if (outputEglSurface == EGL14.EGL_NO_SURFACE && outputSurface.isValid()) {
      outputEglSurface = outputSurface == null ? EGL14.EGL_NO_SURFACE :
        GlUtil.getEglSurface(glResources.eglDisplay, outputSurface);

      int[] width = new int[1];
      EGL14.eglQuerySurface(glResources.eglDisplay, outputEglSurface, EGL14.EGL_WIDTH, width, 0);
      int[] height = new int[1];
      EGL14.eglQuerySurface(glResources.eglDisplay, outputEglSurface, EGL14.EGL_HEIGHT, height, 0);
      outputSurfaceWidth = width[0];
      outputSurfaceHeight = height[0];
    }

    // If there is decoder output, copy it to the current outputSurface.
    if (!waitingForRender && isDecoderSurfacePopulated) {
      // TODO: Currently, the output surface can be destroyed while this code is running. Prevent
      // that happening by blocking the SurfaceView's surfaceDestroyed callback here.
      GlUtil.focusSurface(glResources.eglDisplay, glResources.eglContext, outputEglSurface,
          outputSurfaceWidth, outputSurfaceHeight);
      GLES20.glUseProgram(copyExternalProgram);
      copyExternalUniforms[0].setSamplerTexId(decoderTexId, 0);
      for (int i = 0; i < copyExternalAttributes.length; i++) {
        copyExternalAttributes[i].bind();
      }
      for (int i = 0; i < copyExternalUniforms.length; i++) {
        copyExternalUniforms[i].bind();
      }
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
      EGL14.eglSwapBuffers(glResources.eglDisplay, outputEglSurface);

      // Allow the decoder to render more output to its surface.
      isDecoderSurfacePopulated = false;
    }

    // Continue decoding and rendering to the decoder output surface.
    super.doSomeWork(positionUs, elapsedRealtimeUs);
  }

  @Override
  protected boolean processOutputBuffer(long positionUs, long elapsedRealtimeUs, MediaCodec codec,
      ByteBuffer buffer, MediaCodec.BufferInfo bufferInfo, int bufferIndex, boolean shouldSkip) {
    if (waitingForRender) {
      if (isDecoderSurfacePopulated) {
        decoderSurfaceTexture.updateTexImage();
        waitingForRender = false;
      }
    } else if (!isDecoderSurfacePopulated) {
      if (super.processOutputBuffer(positionUs, elapsedRealtimeUs, codec, buffer, bufferInfo,
          bufferIndex, shouldSkip)) {
        waitingForRender = true;
        return true;
      }
    }
    return false;
  }

  @Override
  protected void flushCodec() throws ExoPlaybackException {
    super.flushCodec();
    resetState();
  }

  @Override
  protected void releaseCodec() {
    super.releaseCodec();
    resetState();
  }

  @Override
  protected void onReleased() {
    if (glResources != null) {
      glResources.release();
      GlUtil.deleteTexture(decoderTexId);
    }
    super.onReleased();
  }

  /**
   * Resets the state machine that waits for decoder output and renders it. This must be called when
   * the decoder is flushed or recreated, as in that case we might be waiting for rendered output to
   * appear on the decoder output surface, but it will actually never arrive.
   */
  private void resetState() {
    waitingForRender = false;
    isDecoderSurfacePopulated = false;
  }

  /** Creates a rendering context and sets up copying from the decoder output surface. */
  private void initializeRendering() throws ExoPlaybackException {
    // Create a context on this thread.
    glResources = new GlResources();
    try {
      glResources.eglDisplay = GlUtil.createEglDisplay();
      glResources.eglContext = GlUtil.createEglContext(glResources.eglDisplay);
      // Use an off-screen buffer so the context still works when the output surface is recreated.
      glResources.eglSurface = GlUtil.createPbufferEglSurface(glResources.eglDisplay);
    } catch (UnsupportedEglVersionException e) {
      throw new ExoPlaybackException("Setting up GL rendering failed.", e);
    }

    // Make the context current so it is possible to use GLES functions.
    GlUtil.focusSurface(
        glResources.eglDisplay, glResources.eglContext, glResources.eglSurface, 1, 1);

    // Set up a shader program to copy from the decoder's external texture to another surface.
    copyExternalProgram = GlUtil.getCopyExternalShaderProgram();
    copyExternalAttributes = GlUtil.getAttributes(copyExternalProgram);
    copyExternalUniforms = GlUtil.getUniforms(copyExternalProgram);
    // The copy program flips the input texture.
    for (int i = 0; i < copyExternalAttributes.length; i++) {
      if (copyExternalAttributes[i].name.equals(GlUtil.POSITION_ATTRIBUTE_NAME)) {
        copyExternalAttributes[i].setBuffer(
            new float[] {-1.0f, -1.0f, 0.0f, 1.0f,
                1.0f, -1.0f, 0.0f, 1.0f,
                -1.0f, 1.0f, 0.0f, 1.0f,
                1.0f, 1.0f, 0.0f, 1.0f,}, 4);
      } else if (copyExternalAttributes[i].name.equals(GlUtil.TEXCOORD_ATTRIBUTE_NAME)) {
        copyExternalAttributes[i].setBuffer(
            new float[] {0.0f, 1.0f, 1.0f,
                1.0f, 1.0f, 1.0f,
                0.0f, 0.0f, 1.0f,
                1.0f, 0.0f, 1.0f,}, 3);
      }
    }

    // Set up the decoder output surface, using a texture identifier we can copy from.
    decoderTexId = GlUtil.generateTexture();
    decoderSurfaceTexture = new SurfaceTexture(decoderTexId);
    Surface decoderSurface = new Surface(decoderSurfaceTexture);
    super.handleMessage(MSG_SET_SURFACE, decoderSurface);
    isDecoderSurfacePopulated = false;

    // This callback runs when the decoder's output surface texture is updated with another frame.
    decoderSurfaceTexture.setOnFrameAvailableListener(
        new SurfaceTexture.OnFrameAvailableListener() {
          @Override
          public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            // Now the texture is populated, we can copy to the output surface in doSomeWork.
            isDecoderSurfacePopulated = true;
          }
        });
  }

  /**
   * Resources associated with the EGL context.
   */
  private static final class GlResources {

    public EGLDisplay eglDisplay;
    public EGLContext eglContext;
    public EGLSurface eglSurface;

    public void release() {
      try {
        GlUtil.destroyEglContext(eglDisplay, eglContext);
      } finally {
        eglDisplay = null;
        eglContext = null;
        eglSurface = null;
      }
    }

  }

}
