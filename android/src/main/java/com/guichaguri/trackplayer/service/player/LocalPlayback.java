package com.guichaguri.trackplayer.service.player;

import android.content.Context;
import android.media.audiofx.Equalizer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.facebook.react.bridge.Promise;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.DatabaseProvider;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;

import com.guichaguri.trackplayer.service.MusicManager;
import com.guichaguri.trackplayer.service.Utils;
import com.guichaguri.trackplayer.service.models.Track;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.NavigableSet;

/**
 * @author Guichaguri
 */
@UnstableApi
public class LocalPlayback extends ExoPlayback<ExoPlayer> {

    private final long cacheMaxSize;

    private SimpleCache cache;
    private boolean prepared = false;
    private Equalizer equalizer;
    private float[] pendingEqualizerLevels;
    private short equalizerNumBands;
    private short[] equalizerBandLevelRange;

    // Crossfade
    private long crossfadeDurationMs = 0;
    private float basePlayerVolume = 1.0f;
    private boolean fadingOut = false;
    private final Handler fadeHandler = new Handler(Looper.getMainLooper());
    private Runnable positionCheckRunnable;
    private Runnable fadeRunnable;
    private static final int FADE_INTERVAL_MS = 50;
    private static final int POSITION_CHECK_INTERVAL_MS = 100;
    public LocalPlayback(Context context, MusicManager manager, ExoPlayer player, long maxCacheSize,
                         boolean autoUpdateMetadata) {
        super(context, manager, player, autoUpdateMetadata);
        this.cacheMaxSize = maxCacheSize;
    }

    @Override
    public void initialize() {
        if(cacheMaxSize > 0) {
            File cacheDir = new File(context.getFilesDir(), "TrackPlayer");
            DatabaseProvider db = new StandaloneDatabaseProvider(context);
            cache = new SimpleCache(cacheDir, new LeastRecentlyUsedCacheEvictor(cacheMaxSize), db);
        } else {
            cache = null;
        }

        super.initialize();

        int audioSessionId = player.getAudioSessionId();
        if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            try {
                equalizer = new Equalizer(0, audioSessionId);
                equalizer.setEnabled(true);
                equalizerNumBands = equalizer.getNumberOfBands();
                equalizerBandLevelRange = equalizer.getBandLevelRange();
                if (pendingEqualizerLevels != null) {
                    applyEqualizerLevels(pendingEqualizerLevels);
                    pendingEqualizerLevels = null;
                }
            } catch (Exception e) {
                Log.e(Utils.LOG, "Failed to create equalizer", e);
            }
        }

        resetQueue();
    }

    public DataSource.Factory enableCaching(DataSource.Factory ds) {
        if(cache == null || cacheMaxSize <= 0) return ds;

        return new CacheDataSource.Factory()
                                .setCache(cache)
                                .setUpstreamDataSourceFactory(ds)
                                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    public void isCached(String url, Promise promise) {
        if (cache == null) {
          promise.resolve(false);
          return;
        }
        NavigableSet<CacheSpan> cachedSpans = cache.getCachedSpans(url);
        promise.resolve(!cachedSpans.isEmpty());
    }

    public void getCacheSize(Promise promise) {
        if (cache != null) {
            promise.resolve((double) cache.getCacheSpace());
        } else {
          promise.resolve(0);
        }
    }

    public void clearCache(Promise promise) {
        if (cache != null) {
            for (String key: cache.getKeys()) {
                try {
                    cache.removeResource(key);
                } catch (Exception e) {
                    Log.e(Utils.LOG, e.getMessage());
                }
            }
        } else {
            Log.d(Utils.LOG, "Cache is not initialized.");
        }
        promise.resolve(null);
    }

    private void prepare() {
        if(!prepared) {
            Log.d(Utils.LOG, "Preparing the media source...");
            player.prepare();
            prepared = true;
        }
    }

    @Override
    public void add(Track track, int index, Promise promise) {
        queue.add(index, track);
        MediaSource trackSource = track.toMediaSource(context, this);
        player.addMediaSource(index, trackSource);
        promise.resolve(index);
        prepare();
    }

    @Override
    public void add(Collection<Track> tracks, int index, Promise promise) {
        List<MediaSource> trackList = new ArrayList<>();

        for(Track track : tracks) {
            trackList.add(track.toMediaSource(context, this));
        }

        queue.addAll(index, tracks);
        player.addMediaSources(index, trackList);
        promise.resolve(index);

        prepare();
    }

    @Override
    public void remove(List<Integer> indexes, Promise promise) {
        int currentIndex = player.getCurrentMediaItemIndex();

        // Sort the list so we can loop through sequentially
        Collections.sort(indexes);

        for(int i = indexes.size() - 1; i >= 0; i--) {
            int index = indexes.get(i);

            // Skip indexes that are the current track or are out of bounds
            if(index == currentIndex || index < 0 || index >= queue.size()) {
                // Resolve the promise when the last index is invalid
                if(i == 0) promise.resolve(null);
                continue;
            }

            queue.remove(index);

            player.removeMediaItem(index);
            if(i == 0) {
              promise.resolve(index);
            }

            // Fix the window index
            if (index < lastKnownWindow) {
                lastKnownWindow--;
            }
        }
    }

    @Override
    public void removeUpcomingTracks() {
        int currentIndex = player.getCurrentMediaItemIndex();
        if (currentIndex == C.INDEX_UNSET) return;

        for (int i = queue.size() - 1; i > currentIndex; i--) {
            queue.remove(i);
            player.removeMediaItem(i);
        }
    }

    @Override
    public void setRepeatMode(int repeatMode) {
        player.setRepeatMode(repeatMode);
    }

    public int getRepeatMode() {
        return player.getRepeatMode();
    }

    private void resetQueue() {
        queue.clear();


        player.clearMediaItems();
        player.prepare();
        prepared = false; // We set it to false as the queue is now empty

        lastKnownWindow = C.INDEX_UNSET;
        lastKnownPosition = C.INDEX_UNSET;

        manager.onReset();
    }

    @Override
    public void play() {
        prepare();
        super.play();
        if (crossfadeDurationMs > 0) startPositionMonitor();
    }

    @Override
    public void pause() {
        cancelFades();
        super.pause();
        stopPositionMonitor();
    }

    @Override
    public void stop() {
        cancelFades();
        super.stop();
        stopPositionMonitor();
        prepared = false;
    }

    @Override
    public void seekTo(long time) {
        if (crossfadeDurationMs > 0) cancelFades();
        prepare();
        super.seekTo(time);
    }

    @Override
    public void reset() {
        cancelFades();
        stopPositionMonitor();
        Integer track = getCurrentTrackIndex();
        long position = player.getCurrentPosition();

        super.reset();
        resetQueue();

        manager.onTrackUpdate(track, position, null, null);
    }

    @Override
    public float getPlayerVolume() {
        return player.getVolume();
    }

    @Override
    public void setPlayerVolume(float volume) {
        basePlayerVolume = volume;
        player.setVolume(volume);
    }

    @Override
    public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
        super.onMediaItemTransition(mediaItem, reason);
        if (crossfadeDurationMs > 0) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                startFadeIn();
            } else {
                cancelFades();
            }
        }
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (playbackState == Player.STATE_ENDED) {
            prepared = false;
            if (crossfadeDurationMs > 0) {
                cancelFades();
                stopPositionMonitor();
            }
        }
        super.onPlaybackStateChanged(playbackState);
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        prepared = false;
        super.onPlayerError(error);
    }

    @Override
    public float getCrossfadeDuration() {
        return crossfadeDurationMs / 1000.0f;
    }

    @Override
    public void setCrossfadeDuration(float seconds) {
        crossfadeDurationMs = (long)(seconds * 1000);
        if (crossfadeDurationMs == 0) {
            cancelFades();
            stopPositionMonitor();
        }
    }

    private void startPositionMonitor() {
        stopPositionMonitor();
        positionCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (!fadingOut && player.getPlayWhenReady()) {
                    long duration = player.getDuration();
                    long position = player.getCurrentPosition();
                    if (duration != C.TIME_UNSET && duration > 0) {
                        long timeRemaining = duration - position;
                        if (timeRemaining > 0 && timeRemaining <= crossfadeDurationMs) {
                            startFadeOut(timeRemaining);
                        }
                    }
                }
                if (crossfadeDurationMs > 0) {
                    fadeHandler.postDelayed(this, POSITION_CHECK_INTERVAL_MS);
                }
            }
        };
        fadeHandler.post(positionCheckRunnable);
    }

    private void stopPositionMonitor() {
        if (positionCheckRunnable != null) {
            fadeHandler.removeCallbacks(positionCheckRunnable);
            positionCheckRunnable = null;
        }
    }

    private void startFadeOut(long remainingMs) {
        fadingOut = true;
        cancelFadeRunnable();

        final float fromVolume = player.getVolume();
        final long startTime = System.currentTimeMillis();
        fadeRunnable = new Runnable() {
            @Override
            public void run() {
                long elapsed = System.currentTimeMillis() - startTime;
                float fraction = Math.min(1.0f, (float) elapsed / remainingMs);
                player.setVolume(fromVolume * (1.0f - fraction));
                if (fraction < 1.0f) {
                    fadeHandler.postDelayed(this, FADE_INTERVAL_MS);
                } else {
                    player.setVolume(0);
                    fadingOut = false;
                }
            }
        };
        fadeHandler.post(fadeRunnable);
    }

    private void startFadeIn() {
        fadingOut = false;
        cancelFadeRunnable();
        player.setVolume(0);
        final long startTime = System.currentTimeMillis();
        fadeRunnable = new Runnable() {
            @Override
            public void run() {
                long elapsed = System.currentTimeMillis() - startTime;
                float fraction = Math.min(1.0f, (float) elapsed / crossfadeDurationMs);
                player.setVolume(basePlayerVolume * fraction);
                if (fraction < 1.0f) {
                    fadeHandler.postDelayed(this, FADE_INTERVAL_MS);
                } else {
                    player.setVolume(basePlayerVolume);
                }
            }
        };
        fadeHandler.post(fadeRunnable);
        startPositionMonitor();
    }

    private void cancelFadeRunnable() {
        if (fadeRunnable != null) {
            fadeHandler.removeCallbacks(fadeRunnable);
            fadeRunnable = null;
        }
    }

    private void cancelFades() {
        fadingOut = false;
        cancelFadeRunnable();
        player.setVolume(basePlayerVolume);
    }

    @Override
    public void destroy() {
        cancelFades();
        stopPositionMonitor();
        super.destroy();

        if(cache != null) {
            try {
                cache.release();
                cache = null;
            } catch(Exception ex) {
                Log.w(Utils.LOG, "Couldn't release the cache properly", ex);
            }
        }

        if (equalizer != null) {
            try {
                equalizer.release();
            } catch (Exception ex) {
                Log.w(Utils.LOG, "Couldn't release the equalizer properly", ex);
            }
            equalizer = null;
        }
    }

    @Override
    public int getAudioSessionId() {
        return player.getAudioSessionId();
    }

    @Override
    public float[] getEqualizerBandLevel() {
        if (equalizer == null) {
            return pendingEqualizerLevels != null ? pendingEqualizerLevels : new float[0];
        }
        float[] levels = new float[equalizerNumBands];
        for (short i = 0; i < equalizerNumBands; i++) {
            levels[i] = equalizer.getBandLevel(i) / 100.0f;
        }
        return levels;
    }

    @Override
    public void setEqualizerBandLevel(float[] levels) {
        if (equalizer == null) {
            pendingEqualizerLevels = levels;
            return;
        }
        applyEqualizerLevels(levels);
    }

    private void applyEqualizerLevels(float[] levels) {
        for (short i = 0; i < Math.min(levels.length, equalizerNumBands); i++) {
            short millibel = (short) Math.max(equalizerBandLevelRange[0], Math.min(equalizerBandLevelRange[1], Math.round(levels[i] * 100)));
            equalizer.setBandLevel(i, millibel);
        }
    }
}