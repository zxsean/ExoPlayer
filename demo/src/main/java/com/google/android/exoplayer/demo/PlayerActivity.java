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
package com.google.android.exoplayer.demo;

import com.google.android.exoplayer.demo.player.DashRendererBuilder;
import com.google.android.exoplayer.demo.player.DemoPlayer;
import com.google.android.exoplayer.demo.player.DemoPlayer.RendererBuilder;
import com.google.android.exoplayer.demo.player.ExtractorRendererBuilder;
import com.google.android.exoplayer.demo.player.HlsRendererBuilder;
import com.google.android.exoplayer.demo.player.SmoothStreamingRendererBuilder;
import com.google.android.exoplayer.extractor.mp3.Mp3Extractor;
import com.google.android.exoplayer.extractor.mp4.Mp4Extractor;
import com.google.android.exoplayer.extractor.ts.AdtsExtractor;
import com.google.android.exoplayer.extractor.ts.TsExtractor;
import com.google.android.exoplayer.extractor.webm.WebmExtractor;
import com.google.android.exoplayer.util.Util;

import android.app.Activity;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.TextureView.SurfaceTextureListener;
import android.view.View;
import android.view.View.OnClickListener;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;

/**
 * An activity that plays media using {@link DemoPlayer}.
 */
public class PlayerActivity extends Activity implements SurfaceTextureListener,
    DemoPlayer.Listener {

  public static final int TYPE_DASH = 0;
  public static final int TYPE_SS = 1;
  public static final int TYPE_HLS = 2;
  public static final int TYPE_MP4 = 3;
  public static final int TYPE_MP3 = 4;
  public static final int TYPE_FMP4 = 5;
  public static final int TYPE_WEBM = 6;
  public static final int TYPE_TS = 7;
  public static final int TYPE_AAC = 8;
  public static final int TYPE_M4A = 9;

  public static final String CONTENT_TYPE_EXTRA = "content_type";
  public static final String CONTENT_ID_EXTRA = "content_id";

  private static final String TAG = "PlayerActivity";

  private static final CookieManager defaultCookieManager;
  static {
    defaultCookieManager = new CookieManager();
    defaultCookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER);
  }

  private static DemoPlayer player;
  private static SurfaceTexture surfaceTexture;
  private static Surface surface;

  private TextureView textureView;

  private Uri contentUri;
  private int contentType;
  private String contentId;

  // Activity lifecycle

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    final Intent intent = getIntent();
    contentUri = intent.getData();
    contentType = intent.getIntExtra(CONTENT_TYPE_EXTRA, -1);
    contentId = intent.getStringExtra(CONTENT_ID_EXTRA);

    setContentView(R.layout.player_activity);

    textureView = (TextureView) findViewById(R.id.texture_view);
    if (surfaceTexture != null) {
      Log.e("XXX", "onCreate:setSurfaceTexture\t"+surface.hashCode()+"\t"+surfaceTexture.hashCode());
      textureView.setSurfaceTexture(surfaceTexture);
    }
    textureView.setSurfaceTextureListener(this);
    textureView.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View view) {
        startActivity(intent);
      }
    });

    CookieHandler currentHandler = CookieHandler.getDefault();
    if (currentHandler != defaultCookieManager) {
      CookieHandler.setDefault(defaultCookieManager);
    }
  }

  @Override
  public void onResume() {
    super.onResume();

    if (player == null) {
      player = new DemoPlayer(getRendererBuilder());
      EventLogger eventLogger = new EventLogger();
      eventLogger.startSession();
      player.addListener(eventLogger);
      player.setInfoListener(eventLogger);
      player.setInternalErrorListener(eventLogger);
      player.prepare();
      player.setPlayWhenReady(true);
    }

    // Attach to player.
    player.addListener(this);
    if (surface != null) {
      Log.e("XXX", "prepare:setSurface\t"+surface.hashCode());
      player.setSurface(surface);
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    player.removeListener(this);
  }

  // Internal methods

  private RendererBuilder getRendererBuilder() {
    String userAgent = Util.getUserAgent(this, "ExoPlayerDemo");
    switch (contentType) {
      case TYPE_SS:
        return new SmoothStreamingRendererBuilder(this, userAgent, contentUri.toString(),
            new SmoothStreamingTestMediaDrmCallback());
      case TYPE_DASH:
        return new DashRendererBuilder(this, userAgent, contentUri.toString(),
            new WidevineTestMediaDrmCallback(contentId), null);
      case TYPE_HLS:
        return new HlsRendererBuilder(this, userAgent, contentUri.toString(), null);
      case TYPE_M4A: // There are no file format differences between M4A and MP4.
      case TYPE_MP4:
        return new ExtractorRendererBuilder(this, userAgent, contentUri, new Mp4Extractor());
      case TYPE_MP3:
        return new ExtractorRendererBuilder(this, userAgent, contentUri, new Mp3Extractor());
      case TYPE_TS:
        return new ExtractorRendererBuilder(this, userAgent, contentUri,
            new TsExtractor(0, null));
      case TYPE_AAC:
        return new ExtractorRendererBuilder(this, userAgent, contentUri, new AdtsExtractor());
      case TYPE_WEBM:
        return new ExtractorRendererBuilder(this, userAgent, contentUri, new WebmExtractor());
      default:
        throw new IllegalStateException("Unsupported type: " + contentType);
    }
  }

  // DemoPlayer.Listener implementation

  @Override
  public void onStateChanged(boolean playWhenReady, int playbackState) {
    Log.i(TAG, "State:\t"+playWhenReady+"\t"+playbackState);
  }

  @Override
  public void onError(Exception e) {
    Log.e(TAG, "Playback error", e);
  }

  @Override
  public void onVideoSizeChanged(int width, int height, float pixelWidthAspectRatio) {
    // Do nothing.
  }

  // SurfaceTexture listener.

  @Override
  public void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h) {
    surfaceTexture = texture;
    surface = new Surface(texture);
    Log.e("XXX", "onSurfaceTextureAvailable\t"+surface.hashCode()+"\t"+surfaceTexture.hashCode());
    player.setSurface(surface);
  }

  @Override
  public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
    return false;
  }

  @Override
  public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) {
    // Do nothing.
  }

  @Override
  public void onSurfaceTextureUpdated(SurfaceTexture texture) {
    // Do nothing.
  }

}
