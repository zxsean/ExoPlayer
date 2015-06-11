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

import android.annotation.TargetApi;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * Wrappers for EGL/GLES functions.
 */
@TargetApi(17)
public class GlUtil {

  public static final String POSITION_ATTRIBUTE_NAME = "a_position";
  public static final String TEXCOORD_ATTRIBUTE_NAME = "a_texcoord";

  public static final int NO_FBO = 0;

  /** Vertex shader that renders a quad filling the viewport. */
  private static final String BLIT_VERTEX_SHADER = String.format(Locale.US,
      "attribute vec4 %1$s;\n" +
      "attribute vec3 %2$s;\n" +
      "varying vec2 v_texcoord;\n" +
      "void main() {\n" +
      "  gl_Position = %1$s;\n" +
      "  v_texcoord = %2$s.xy;\n" +
      "}\n", POSITION_ATTRIBUTE_NAME, TEXCOORD_ATTRIBUTE_NAME);

  /** Fragment shader that renders from an external shader to the current target. */
  private static final String COPY_EXTERNAL_FRAGMENT_SHADER =
      "#extension GL_OES_EGL_image_external : require\n" +
          "precision mediump float;\n" +
          "uniform samplerExternalOES tex_sampler_0;\n" +
          "varying vec2 v_texcoord;\n" +
          "void main() {\n" +
          "  gl_FragColor = texture2D(tex_sampler_0, v_texcoord);\n" +
          "}\n";

  public static final class UnsupportedEglVersionException extends Exception {}

  /**
   * GL attribute, which can be attached to a buffer with {@link Attribute#setBuffer(float[], int)}.
   */
  public static final class Attribute {

    public final String name;

    private final int index;
    private final int location;

    private Buffer buffer;
    private int size;

    public Attribute(int program, int index) {
      int[] len = new int[1];
      GLES20.glGetProgramiv(program, GLES20.GL_ACTIVE_ATTRIBUTE_MAX_LENGTH, len, 0);

      int[] type = new int[1];
      int[] size = new int[1];
      byte[] nameBytes = new byte[len[0]];
      int[] ignore = new int[1];

      GLES20.glGetActiveAttrib(program, index, len[0], ignore, 0, size, 0, type, 0, nameBytes, 0);
      name = new String(nameBytes, 0, strlen(nameBytes));
      location = GLES20.glGetAttribLocation(program, name);
      this.index = index;
    }

    /**
     * Configures {@link #bind()} to attach vertices in {@code buffer} (each of size
     * {@code size} elements) to this {@link Attribute}.
     *
     * @param buffer Buffer to bind to this attribute.
     * @param size Number of elements per vertex.
     */
    public void setBuffer(float[] buffer, int size) {
      if (buffer == null) {
        throw new IllegalArgumentException("buffer must be non-null");
      }

      this.buffer = getVertexBuffer(buffer);
      this.size = size;
    }

    /**
     * Sets the vertex attribute to whatever was attached via {@link #setBuffer(float[], int)}.
     *
     * <p>Should be called before each drawing call.
     */
    public void bind() {
      if (buffer == null) {
        throw new IllegalStateException("call setBuffer before bind");
      }

      GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
      GLES20.glVertexAttribPointer(
          location,
          size, // count
          GLES20.GL_FLOAT, // type
          false, // normalize
          0, // stride
          buffer);
      GLES20.glEnableVertexAttribArray(index);
      checkGlError();
    }

  }

  /**
   * GL uniform, which can be attached to a sampler using
   * {@link Uniform#setSamplerTexId(int, int)}.
   */
  public static final class Uniform {

    private final String name;
    private final int location;
    private final int type;

    private int texId;
    private int unit;

    public Uniform(int program, int index) {
      int[] len = new int[1];
      GLES20.glGetProgramiv(program, GLES20.GL_ACTIVE_UNIFORM_MAX_LENGTH, len, 0);

      int[] type = new int[1];
      int[] size = new int[1];
      byte[] name = new byte[len[0]];
      int[] ignore = new int[1];

      GLES20.glGetActiveUniform(program, index, len[0], ignore, 0, size, 0, type, 0, name, 0);
      this.name = new String(name, 0, strlen(name));
      location = GLES20.glGetUniformLocation(program, this.name);
      this.type = type[0];
    }

    /**
     * Configures {@link #bind()} to use the specified {@code texId} for this sampler uniform.
     *
     * @param texId from which to sample
     * @param unit for this texture
     */
    public void setSamplerTexId(int texId, int unit) {
      this.texId = texId;
      this.unit = unit;
    }

    /**
     * Sets the uniform to whatever was attached via {@link #setSamplerTexId(int, int)}.
     *
     * <p>Should be called before each drawing call.
     */
    public void bind() {
      if (texId == 0) {
        throw new IllegalStateException("call setSamplerTexId before bind");
      }

      GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
      if (type == GLES11Ext.GL_SAMPLER_EXTERNAL_OES) {
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
      } else if (type == GLES20.GL_SAMPLER_2D) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
      } else {
        throw new IllegalStateException("unexpected uniform type: " + type);
      }
      GLES20.glUniform1i(location, unit);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
          GLES20.GL_CLAMP_TO_EDGE);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
          GLES20.GL_CLAMP_TO_EDGE);
      checkGlError();
    }

  }

  /**
   * Returns an initialized default display.
   */
  public static EGLDisplay createEglDisplay() {
    EGLDisplay eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
    if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
      throw new IllegalStateException("no EGL display");
    }

    int[] major = new int[1];
    int[] minor = new int[1];
    if (!EGL14.eglInitialize(eglDisplay, major, 0, minor, 0)) {
      throw new IllegalStateException("error in eglInitialize");
    }

    return eglDisplay;
  }

  /**
   * Returns a new GL context for the specified {@code eglDisplay}.
   *
   * @throws UnsupportedEglVersionException if the device does not support EGL version 2.
   *         {@code eglDisplay} is terminated before the exception is thrown in this case.
   */
  public static EGLContext createEglContext(EGLDisplay eglDisplay)
      throws UnsupportedEglVersionException {
    int[] contextAttributes = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
    EGLContext eglContext = EGL14.eglCreateContext(eglDisplay, getEglConfig(eglDisplay),
        EGL14.EGL_NO_CONTEXT, contextAttributes, 0);

    if (eglContext == null) {
      EGL14.eglTerminate(eglDisplay);
      throw new UnsupportedEglVersionException();
    }

    return eglContext;
  }

  /**
   * Returns a new {@link EGLSurface} wrapping the specified {@code surface}.
   *
   * @param eglDisplay Display to which to attach the surface.
   * @param surface Surface to wrap; must be a surface, surface texture or surface holder.
   */
  public static EGLSurface getEglSurface(EGLDisplay eglDisplay, Object surface) {
    return EGL14.eglCreateWindowSurface(eglDisplay, getEglConfig(eglDisplay), surface,
        new int[] { EGL14.EGL_NONE }, 0);
  }

  /**
   * Returns a new off-screen {@link EGLSurface} associated with a 1 * 1 pixel buffer.
   *
   * @param eglDisplay Display to which to attach the surface.
   */
  public static EGLSurface createPbufferEglSurface(EGLDisplay eglDisplay) {
    return EGL14.eglCreatePbufferSurface(eglDisplay, getEglConfig(eglDisplay),
        new int[] { EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE }, 0);
  }

  /**
   * Makes the specified {@code surface} the render target, using a viewport of {@code width} by
   * {@code height} pixels.
   */
  public static void focusSurface(EGLDisplay eglDisplay, EGLContext eglContext, EGLSurface surface,
      int width, int height) {
    EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext);
    GLES20.glViewport(0, 0, width, height);
    GLES20.glScissor(0, 0, width, height);
  }

  /**
   * Destroys the GL context identified by {@code eglDisplay} and {@code eglContext}.
   */
  public static void destroyEglContext(EGLDisplay eglDisplay, EGLContext eglContext) {
    if (eglDisplay == null) {
      return;
    }

    EGL14.eglMakeCurrent(eglDisplay,
        EGL14.EGL_NO_SURFACE,
        EGL14.EGL_NO_SURFACE,
        EGL14.EGL_NO_CONTEXT);
    int error = EGL14.eglGetError();
    if (error != EGL14.EGL_SUCCESS) {
      throw new RuntimeException("error releasing context: " + error);
    }

    if (eglContext != null) {
      EGL14.eglDestroyContext(eglDisplay, eglContext);
      error = EGL14.eglGetError();
      if (error != EGL14.EGL_SUCCESS) {
        throw new RuntimeException("error destroying context: " + error);
      }
    }

    // Do not call EGL14.eglReleaseThread() here due to a crash on some Samsung devices.
    // This may leak a small amount of thread-local state, but this method is called rarely.
    // EGL14.eglReleaseThread();

    EGL14.eglTerminate(eglDisplay);
    error = EGL14.eglGetError();
    if (error != EGL14.EGL_SUCCESS) {
      throw new RuntimeException("error terminating display: " + error);
    }
  }

  /**
   * Returns a new GL texture identifier.
   */
  public static int generateTexture() {
    int[] textures = new int[1];
    GLES20.glGenTextures(1, textures, 0);
    checkGlError();
    return textures[0];
  }

  /**
   * Deletes a GL texture.
   *
   * @param texId of the texture to delete
   */
  public static void deleteTexture(int texId) {
    int[] textures = new int[] { texId };
    GLES20.glDeleteTextures(1, textures, 0);
  }

  /**
   * Returns the {@link Attribute}s in the specified {@code program}.
   */
  public static Attribute[] getAttributes(int program) {
    int[] attributeCount = new int[1];
    GLES20.glGetProgramiv(program, GLES20.GL_ACTIVE_ATTRIBUTES, attributeCount, 0);
    if (attributeCount[0] != 2) {
      throw new IllegalStateException("expected two attributes");
    }

    Attribute[] attributes = new Attribute[attributeCount[0]];
    for (int i = 0; i < attributeCount[0]; i++) {
      attributes[i] = new Attribute(program, i);
    }
    return attributes;
  }

  /**
   * Returns the {@link Uniform}s in the specified {@code program}.
   */
  public static Uniform[] getUniforms(int program) {
    int[] uniformCount = new int[1];
    GLES20.glGetProgramiv(program, GLES20.GL_ACTIVE_UNIFORMS, uniformCount, 0);

    Uniform[] uniforms = new Uniform[uniformCount[0]];
    for (int i = 0; i < uniformCount[0]; i++) {
      uniforms[i] = new Uniform(program, i);
    }

    return uniforms;
  }

  /**
   * Returns a GL shader program identifier for a compiled program that copies from an external
   * texture.
   *
   * <p>It has two vertex attributes, {@link #POSITION_ATTRIBUTE_NAME} and
   * {@link #TEXCOORD_ATTRIBUTE_NAME} which should be set to the output position (vec4) and
   * texture coordinates (vec3) respectively of the output quad.
   */
  public static int getCopyExternalShaderProgram() {
    int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, BLIT_VERTEX_SHADER);
    int fragmentShader =
        compileShader(GLES20.GL_FRAGMENT_SHADER, COPY_EXTERNAL_FRAGMENT_SHADER);
    return linkProgram(vertexShader, fragmentShader);
  }

  private static EGLConfig getEglConfig(EGLDisplay eglDisplay) {
    // Get an EGLConfig.
    final int EGL_OPENGL_ES2_BIT = 4;
    final int RED_SIZE = 8;
    final int GREEN_SIZE = 8;
    final int BLUE_SIZE = 8;
    final int ALPHA_SIZE = 8;
    final int DEPTH_SIZE = 0;
    final int STENCIL_SIZE = 0;
    final int[] DEFAULT_CONFIGURATION = new int[] {
        EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL14.EGL_RED_SIZE, RED_SIZE,
        EGL14.EGL_GREEN_SIZE, GREEN_SIZE,
        EGL14.EGL_BLUE_SIZE, BLUE_SIZE,
        EGL14.EGL_ALPHA_SIZE, ALPHA_SIZE,
        EGL14.EGL_DEPTH_SIZE, DEPTH_SIZE,
        EGL14.EGL_STENCIL_SIZE, STENCIL_SIZE,
        EGL14.EGL_NONE};

    int[] configsCount = new int[1];
    EGLConfig[] eglConfigs = new EGLConfig[1];
    if (!EGL14.eglChooseConfig(
        eglDisplay, DEFAULT_CONFIGURATION, 0, eglConfigs, 0, 1, configsCount, 0)) {
      throw new RuntimeException("eglChooseConfig failed");
    }
    return eglConfigs[0];
  }

  private static int compileShader(int type, String source) {
    int shader = GLES20.glCreateShader(type);
    if (shader == 0) {
      throw new RuntimeException("could not create shader: " + GLES20.glGetError());
    }

    GLES20.glShaderSource(shader, source);
    GLES20.glCompileShader(shader);
    int[] compiled = new int[1];
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
    if (compiled[0] == 0) {
      String info = GLES20.glGetShaderInfoLog(shader);
      GLES20.glDeleteShader(shader);
      throw new RuntimeException("could not compile shader " + type + ":" + info);
    }

    return shader;
  }

  private static int linkProgram(int vertexShader, int fragmentShader) {
    int program = GLES20.glCreateProgram();
    if (program == 0) {
      throw new RuntimeException("could not create shader program");
    }
    GLES20.glAttachShader(program, vertexShader);
    GLES20.glAttachShader(program, fragmentShader);
    GLES20.glLinkProgram(program);
    int[] linked = new int[1];
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
    if (linked[0] != GLES20.GL_TRUE) {
      String info = GLES20.glGetProgramInfoLog(program);
      GLES20.glDeleteProgram(program);
      throw new RuntimeException("could not link shader " + info);
    }

    return program;
  }

  /**
   * Returns a {@link Buffer} containing the specified floats, suitable for passing to
   * {@link GLES20#glVertexAttribPointer(int, int, int, boolean, int, Buffer)}.
   */
  private static Buffer getVertexBuffer(float[] values) {
    final int FLOAT_SIZE = 4;
    return ByteBuffer.allocateDirect(values.length * FLOAT_SIZE)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(values)
        .position(0);
  }

  /**
   * Returns the length of the null-terminated string in {@code strVal}.
   */
  private static int strlen(byte[] strVal) {
    for (int i = 0; i < strVal.length; ++i) {
      if (strVal[i] == '\0') {
        return i;
      }
    }
    return strVal.length;
  }

  /**
   * Checks for a GL error using {@link GLES20#glGetError()}.
   *
   * @throws RuntimeException if there is a GL error
   */
  private static void checkGlError() {
    int errorCode;
    if ((errorCode = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
      throw new RuntimeException("gl error: " + Integer.toHexString(errorCode));
    }
  }

}
