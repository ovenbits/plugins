package io.flutter.plugins.videoplayer;

import static com.google.android.exoplayer2.Player.REPEAT_MODE_ALL;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_OFF;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Player.EventListener;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.analytics.PlaybackStatsListener;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector;
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.util.Util;
import io.flutter.plugin.common.EventChannel;
import io.flutter.view.TextureRegistry;
import com.google.android.exoplayer2.ui.PlayerNotificationManager;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class VideoPlayer {
  private static final String FORMAT_SS = "ss";
  private static final String FORMAT_DASH = "dash";
  private static final String FORMAT_HLS = "hls";
  private static final String FORMAT_OTHER = "other";

  private SimpleExoPlayer exoPlayer;

  private Surface surface;

  private final TextureRegistry.SurfaceTextureEntry textureEntry;

  private QueuingEventSink eventSink = new QueuingEventSink();

  private final EventChannel eventChannel;

  private boolean isInitialized = false;

  private PlaybackStatsListener playbackStatsListener = new PlaybackStatsListener(false, null);

  private MediaSessionCompat mediaSession;
  private MediaSessionConnector mediaSessionConnector;
  private PlayerNotificationManager playerNotificationManager;
  private MediaMetadataCompat mediaMetadata;

  VideoPlayer(
      Context context,
      EventChannel eventChannel,
      TextureRegistry.SurfaceTextureEntry textureEntry,
      String dataSource,
      String formatHint) {
    this.eventChannel = eventChannel;
    this.textureEntry = textureEntry;

    TrackSelector trackSelector = new DefaultTrackSelector();
    exoPlayer = ExoPlayerFactory.newSimpleInstance(context, trackSelector);

    mediaSession = new MediaSessionCompat(context, "media-session");
    mediaSessionConnector = new MediaSessionConnector(mediaSession);
    mediaSessionConnector.setPlayer(exoPlayer);
    mediaSessionConnector.setQueueNavigator(new TimelineQueueNavigator(mediaSession) {
      @Override
      public MediaDescriptionCompat getMediaDescription(Player player, int windowIndex) {
        if (mediaMetadata == null) {
          return new MediaDescriptionCompat.Builder().build();
        }
        return mediaMetadata.getDescription();
      }
    });
    mediaSessionConnector.setEnabledPlaybackActions(
            PlaybackStateCompat.ACTION_PLAY_PAUSE
                    | PlaybackStateCompat.ACTION_PLAY
                    | PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_SEEK_TO
                    | PlaybackStateCompat.ACTION_FAST_FORWARD
                    | PlaybackStateCompat.ACTION_REWIND
                    | PlaybackStateCompat.ACTION_STOP
    );

    String notificationChannelId = context.getPackageName() + ".video_channel";

    if (Util.SDK_INT >= 26) {
      NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
      NotificationChannel channel = new NotificationChannel(notificationChannelId, "Video Player Controls", NotificationManager.IMPORTANCE_LOW);
      notificationManager.createNotificationChannel(channel);
    }

    playerNotificationManager = new PlayerNotificationManager(
            context,
            notificationChannelId,
            316,
            new PlayerNotificationManager.MediaDescriptionAdapter() {
              @Override
              public CharSequence getCurrentContentTitle(Player player) {
                if (mediaMetadata == null) {
                  return "";
                }
                return mediaMetadata.getDescription().getTitle();
              }

              @Nullable
              @Override
              public PendingIntent createCurrentContentIntent(Player player) {
                Intent intent = new Intent(context, VideoPlayer.class);
                return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
              }

              @Nullable
              @Override
              public CharSequence getCurrentContentText(Player player) {
                if (mediaMetadata == null) {
                  return "";
                }
                return mediaMetadata.getDescription().getSubtitle();
              }

              @Nullable
              @Override
              public Bitmap getCurrentLargeIcon(Player player, PlayerNotificationManager.BitmapCallback callback) {
                if (mediaMetadata != null) {
                  Uri artworkUri = mediaMetadata.getDescription().getIconUri();
                  if (artworkUri != null) {
                    new DownloadImageTask(callback).execute(artworkUri.toString());
                  }
                }

                return null;
              }
            }
    );
    playerNotificationManager.setPlayer(exoPlayer);
    playerNotificationManager.setMediaSessionToken(mediaSession.getSessionToken());

    Uri uri = Uri.parse(dataSource);

    DataSource.Factory dataSourceFactory;
    if (isHTTP(uri)) {
      dataSourceFactory =
          new DefaultHttpDataSourceFactory(
              "ExoPlayer",
              null,
              DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS,
              DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS,
              true);
    } else {
      dataSourceFactory = new DefaultDataSourceFactory(context, "ExoPlayer");
    }

    MediaSource mediaSource = buildMediaSource(uri, dataSourceFactory, formatHint, context);
    exoPlayer.prepare(mediaSource);

    setupVideoPlayer(eventChannel, textureEntry);
  }

  private static boolean isHTTP(Uri uri) {
    if (uri == null || uri.getScheme() == null) {
      return false;
    }
    String scheme = uri.getScheme();
    return scheme.equals("http") || scheme.equals("https");
  }

  private MediaSource buildMediaSource(
      Uri uri, DataSource.Factory mediaDataSourceFactory, String formatHint, Context context) {
    int type;
    if (formatHint == null) {
      type = Util.inferContentType(uri.getLastPathSegment());
    } else {
      switch (formatHint) {
        case FORMAT_SS:
          type = C.TYPE_SS;
          break;
        case FORMAT_DASH:
          type = C.TYPE_DASH;
          break;
        case FORMAT_HLS:
          type = C.TYPE_HLS;
          break;
        case FORMAT_OTHER:
          type = C.TYPE_OTHER;
          break;
        default:
          type = -1;
          break;
      }
    }
    switch (type) {
      case C.TYPE_SS:
        return new SsMediaSource.Factory(
                new DefaultSsChunkSource.Factory(mediaDataSourceFactory),
                new DefaultDataSourceFactory(context, null, mediaDataSourceFactory))
            .createMediaSource(uri);
      case C.TYPE_DASH:
        return new DashMediaSource.Factory(
                new DefaultDashChunkSource.Factory(mediaDataSourceFactory),
                new DefaultDataSourceFactory(context, null, mediaDataSourceFactory))
            .createMediaSource(uri);
      case C.TYPE_HLS:
        return new HlsMediaSource.Factory(mediaDataSourceFactory).createMediaSource(uri);
      case C.TYPE_OTHER:
        return new ExtractorMediaSource.Factory(mediaDataSourceFactory)
            .setExtractorsFactory(new DefaultExtractorsFactory())
            .createMediaSource(uri);
      default:
        {
          throw new IllegalStateException("Unsupported type: " + type);
        }
    }
  }

  private void setupVideoPlayer(
      EventChannel eventChannel, TextureRegistry.SurfaceTextureEntry textureEntry) {

    eventChannel.setStreamHandler(
        new EventChannel.StreamHandler() {
          @Override
          public void onListen(Object o, EventChannel.EventSink sink) {
            eventSink.setDelegate(sink);
          }

          @Override
          public void onCancel(Object o) {
            eventSink.setDelegate(null);
          }
        });

    surface = new Surface(textureEntry.surfaceTexture());
    exoPlayer.setVideoSurface(surface);
    setAudioAttributes(exoPlayer);

    exoPlayer.addListener(
        new EventListener() {

          @Override
          public void onPlayerStateChanged(final boolean playWhenReady, final int playbackState) {
            if (playbackState == Player.STATE_BUFFERING) {
              sendBufferingUpdate();
            } else if (playbackState == Player.STATE_READY) {
              if (!isInitialized) {
                isInitialized = true;
                sendInitialized();
              }
            } else if (playbackState == Player.STATE_ENDED) {
              Map<String, Object> event = new HashMap<>();
              event.put("event", "completed");
              eventSink.success(event);
            }
          }

          @Override
          public void onPlayerError(final ExoPlaybackException error) {
            if (eventSink != null) {
              eventSink.error("VideoError", "Video player had error " + error, null);
            }
          }
        });

    exoPlayer.addAnalyticsListener(playbackStatsListener);
  }

  void sendBufferingUpdate() {
    Map<String, Object> event = new HashMap<>();
    event.put("event", "bufferingUpdate");
    List<? extends Number> range = Arrays.asList(0, exoPlayer.getBufferedPosition());
    // iOS supports a list of buffered ranges, so here is a list with a single range.
    event.put("values", Collections.singletonList(range));
    eventSink.success(event);
  }

  @SuppressWarnings("deprecation")
  private static void setAudioAttributes(SimpleExoPlayer exoPlayer) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      exoPlayer.setAudioAttributes(
          new AudioAttributes.Builder().setContentType(C.CONTENT_TYPE_MOVIE).build());
    } else {
      exoPlayer.setAudioStreamType(C.STREAM_TYPE_MUSIC);
    }
  }

  void play() {
    if (mediaSession != null) {
      mediaSession.setActive(true);
    }
    exoPlayer.setPlayWhenReady(true);
  }

  void pause() {
    exoPlayer.setPlayWhenReady(false);
  }

  void setLooping(boolean value) {
    exoPlayer.setRepeatMode(value ? REPEAT_MODE_ALL : REPEAT_MODE_OFF);
  }

  void setVolume(double value) {
    float bracketedValue = (float) Math.max(0.0, Math.min(1.0, value));
    exoPlayer.setVolume(bracketedValue);
  }

  void seekTo(int location) {
    exoPlayer.seekTo(location);
  }

  long getPosition() {
    return exoPlayer.getCurrentPosition();
  }

  long getDurationWatched() {
    return playbackStatsListener.getPlaybackStats().getTotalPlayTimeMs();
  }

  void setSpeed(double value) {
    float bracketedValue = (float) value;
    PlaybackParameters existingParam = exoPlayer.getPlaybackParameters();
    PlaybackParameters newParameter =
            new PlaybackParameters(bracketedValue, existingParam.pitch, existingParam.skipSilence);
    exoPlayer.setPlaybackParameters(newParameter);
  }

  void updateMediaItemInfo(HashMap info) {
    mediaMetadata = createMediaMetadata(
            (String)info.get("id"),
            (String)info.get("album"),
            (String)info.get("title"),
            (String)info.get("artist"),
            (String)info.get("genre"),
            getLong(info.get("duration")),
            (String)info.get("artUri"),
            (String)info.get("displayTitle"),
            (String)info.get("displaySubtitle"),
            (String)info.get("displayDescription")
    );
    updateNowPlaying();
  }

  void clearMediaItemInfo() {
    mediaMetadata = null;
    updateNowPlaying();
  }

  private void updateNowPlaying() {
    mediaSessionConnector.invalidateMediaSessionMetadata();
    playerNotificationManager.setPlayer(mediaMetadata == null ? null : exoPlayer);
    playerNotificationManager.setMediaSessionToken(mediaMetadata == null ? null : mediaSession.getSessionToken());
  }

  @SuppressWarnings("SuspiciousNameCombination")
  private void sendInitialized() {
    if (isInitialized) {
      Map<String, Object> event = new HashMap<>();
      event.put("event", "initialized");
      event.put("duration", exoPlayer.getDuration());
      event.put("durationWatched", playbackStatsListener.getPlaybackStats().getTotalPlayTimeMs());

      if (exoPlayer.getVideoFormat() != null) {
        Format videoFormat = exoPlayer.getVideoFormat();
        int width = videoFormat.width;
        int height = videoFormat.height;
        int rotationDegrees = videoFormat.rotationDegrees;
        // Switch the width/height if video was taken in portrait mode
        if (rotationDegrees == 90 || rotationDegrees == 270) {
          width = exoPlayer.getVideoFormat().height;
          height = exoPlayer.getVideoFormat().width;
        }
        event.put("width", width);
        event.put("height", height);
      }
      eventSink.success(event);
    }
  }

  void dispose() {
    if (isInitialized) {
      exoPlayer.stop();
    }
    textureEntry.release();
    eventChannel.setStreamHandler(null);
    if (surface != null) {
      surface.release();
    }
    if (exoPlayer != null) {
      playerNotificationManager.setPlayer(null);
      exoPlayer.release();
    }
    if (mediaSession != null) {
      mediaSession.setActive(false);
      mediaSession.release();
    }
  }

  private MediaMetadataCompat createMediaMetadata(String mediaId, String album, String title, String artist, String genre, Long duration, String artUri, String displayTitle, String displaySubtitle, String displayDescription) {
    MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaId)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title);
    if (artist != null)
      builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist);
    if (genre != null)
      builder.putString(MediaMetadataCompat.METADATA_KEY_GENRE, genre);
    if (duration != null)
      builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
    if (artUri != null) {
      builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artUri);
    }
    if (displayTitle != null)
      builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title);
    if (displaySubtitle != null)
      builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, displaySubtitle);
    if (displayDescription != null)
      builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, displayDescription);
    return builder.build();
  }

  private static Long getLong(Object o) {
    return (o == null || o instanceof Long) ? (Long)o : Long.valueOf((Integer) o);
  }

  private static class DownloadImageTask extends AsyncTask<String, Void, Bitmap> {
    PlayerNotificationManager.BitmapCallback callback;
    DownloadImageTask(PlayerNotificationManager.BitmapCallback callback) {
      this.callback = callback;
    }

    protected Bitmap doInBackground(String... urls) {
      String url = urls[0];
      Bitmap bmp = null;
      try {
        InputStream in = new java.net.URL(url).openStream();
        bmp = BitmapFactory.decodeStream(in);
      } catch (Exception ignored) {}
      return bmp;
    }
    protected void onPostExecute(Bitmap result) {
      callback.onBitmap(result);
    }
  }

}
