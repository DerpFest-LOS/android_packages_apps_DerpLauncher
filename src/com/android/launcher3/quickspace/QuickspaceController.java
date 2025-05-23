/*
 * Copyright (C) 2018 CypherOS
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
package com.android.launcher3.quickspace;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;
import android.media.RemoteController;
import android.os.Handler;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import com.android.launcher3.R;

import com.android.launcher3.notification.NotificationKeyData;
import com.android.launcher3.notification.NotificationListener;
import com.android.launcher3.util.PackageUserKey;

import java.util.ArrayList;
import java.util.List;

public class QuickspaceController implements NotificationListener.NotificationsChangedListener {

    public final ArrayList<OnDataListener> mListeners = new ArrayList();
    private static final String SETTING_WEATHER_LOCKSCREEN_UNIT = "weather_lockscreen_unit";
    private static final boolean DEBUG = false;
    private static final String TAG = "Launcher3:QuickspaceController";

    private Context mContext;
    private final Handler mHandler;
    private QuickEventsController mEventsController;

    private boolean mUseImperialUnit;

    private AudioManager mAudioManager;
    private Metadata mMetadata = new Metadata();
    private RemoteController mRemoteController;
    private boolean mClientLost = true;
    private boolean mMediaActive = false;
    private String mLastMediaInfo = "";

    private String weatherText = "";
    private Icon weatherIcon;

    public interface OnDataListener {
        void onDataUpdated();
    }

    public QuickspaceController(Context context) {
        mContext = context;
        mHandler = new Handler();
        mEventsController = new QuickEventsController(context);
        mRemoteController = new RemoteController(context, mRCClientUpdateListener);
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mAudioManager.registerRemoteController(mRemoteController);
    }

    private void addWeatherProvider() {
    }

    public void addListener(OnDataListener listener) {
        mListeners.add(listener);
        addWeatherProvider();
        listener.onDataUpdated();
    }

    public void removeListener(OnDataListener listener) {
        mListeners.remove(listener);
    }

    public boolean isQuickEvent() {
        return mEventsController.isQuickEvent();
    }

    public QuickEventsController getEventController() {
        return mEventsController;
    }

    public boolean isWeatherAvailable() {
        return !TextUtils.isEmpty(weatherText) && weatherIcon != null;
    }

    public Icon getWeatherIcon() {
        return weatherIcon != null ? weatherIcon :
            Icon.createWithResource(mContext, R.drawable.ic_warning);
    }

    public String getWeatherTemp() {
        return weatherText;
    }

    private void playbackStateUpdate(int state) {
        boolean active;
        switch (state) {
            case RemoteControlClient.PLAYSTATE_PLAYING:
                active = true;
                break;
            case RemoteControlClient.PLAYSTATE_ERROR:
            case RemoteControlClient.PLAYSTATE_PAUSED:
            case RemoteControlClient.PLAYSTATE_STOPPED:
                active = false;
                break;
            default:
                return;
        }
        if (active != mMediaActive) {
            mMediaActive = active;
        }
        updateMediaInfo();
    }

    public void updateMediaInfo() {
        if (mEventsController == null) return;

        String mediaInfo = mMetadata.trackTitle + mMetadata.trackArtist +
            String.valueOf(mClientLost) + String.valueOf(mMediaActive);

        if (!mediaInfo.equals(mLastMediaInfo)) {
            mLastMediaInfo = mediaInfo;
            mEventsController.setMediaInfo(mMetadata.trackTitle, mMetadata.trackArtist, mClientLost, mMediaActive);
            mEventsController.updateQuickEvents();
            notifyListeners();
        }
    }

    @Override
    public void onNotificationPosted(PackageUserKey postedPackageUserKey,
                                     NotificationKeyData notificationKey) {
        updateMediaInfo();
    }

    @Override
    public void onNotificationRemoved(PackageUserKey removedPackageUserKey,
                                      NotificationKeyData notificationKey) {
        updateMediaInfo();
    }

    @Override
    public void onNotificationFullRefresh(List<StatusBarNotification> activeNotifications) {
        updateMediaInfo();
    }

    public void onPause() {
        if (mEventsController != null) mEventsController.onPause();
    }

    public void onResume() {
        if (mEventsController != null) {
            updateMediaInfo();
            mEventsController.onResume();
            notifyListeners();
        }
    }

    public void weatherUpdated() {
        queryAndUpdateWeather();
    }

    private void queryAndUpdateWeather() {
        updateWeather();
    }

    public void weatherError(int errorReason) {
        Log.d(TAG, "weatherError " + errorReason);
        notifyListeners();
    }

    public void updateSettings() {
        Log.i(TAG, "updateSettings");
        notifyListeners();
    }

    private void updateWeather() {
        try {
            notifyListeners();
        } catch(Exception e) {
            // Do nothing
        }
    }

    public void notifyListeners() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                for (OnDataListener list : mListeners) {
                    list.onDataUpdated();
                }
            }
        });
    }

    public void updateWeatherData(String text, Bitmap image) {
        weatherText = text;
        weatherIcon = image == null ? null : Icon.createWithBitmap(image);
        updateWeather();
    }

   private RemoteController.OnClientUpdateListener mRCClientUpdateListener =
            new RemoteController.OnClientUpdateListener() {

        @Override
        public void onClientChange(boolean clearing) {
            if (clearing) {
                mMetadata.clear();
                mMediaActive = false;
                mClientLost = true;
            }
            updateMediaInfo();
        }

        @Override
        public void onClientPlaybackStateUpdate(int state, long stateChangeTimeMs,
                long currentPosMs, float speed) {
            mClientLost = false;
            playbackStateUpdate(state);
        }

        @Override
        public void onClientPlaybackStateUpdate(int state) {
            mClientLost = false;
            playbackStateUpdate(state);
        }

        @Override
        public void onClientMetadataUpdate(RemoteController.MetadataEditor data) {
            mMetadata.trackTitle = data.getString(MediaMetadataRetriever.METADATA_KEY_TITLE,
                    mMetadata.trackTitle);
            mMetadata.trackArtist = data.getString(MediaMetadataRetriever.METADATA_KEY_ARTIST,
                    mMetadata.trackArtist);
            mClientLost = false;
            updateMediaInfo();
        }

        @Override
        public void onClientTransportControlUpdate(int transportControlFlags) {
        }
    };

    class Metadata {
        private String trackTitle;
        private String trackArtist;

         public void clear() {
            trackTitle = null;
            trackArtist = null;
        }
    }
}
