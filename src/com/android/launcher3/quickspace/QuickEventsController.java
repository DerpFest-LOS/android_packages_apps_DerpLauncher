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

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.View;
import android.view.View.OnClickListener;

import com.android.launcher3.Launcher;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;

import java.util.Calendar;
import java.util.Random;

public class QuickEventsController {

    private Context mContext;

    private String mEventTitle;
    private String mEventTitleSub;
    private OnClickListener mEventTitleSubAction = null;
    private int mEventSubIcon;

    private boolean mIsQuickEvent = false;
    private boolean mRunning = true;
    private boolean mRegistered = false;

    // Device Intro
    private long mInitTimestamp = 0;
    private int mIntroTimeout = 0;

    // PSA + Personality
    private String[] mPSAMorningStr;
    private String[] mPSAEvenStr;
    private String[] mPSAMidniteStr;
    private String[] mPSARandomStr;
    private BroadcastReceiver mPSAListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            psonalityEvent();
        }
    };

    // NowPlaying
    private boolean mEventNowPlaying = false;
    private String mNowPlayingTitle;
    private String mNowPlayingArtist;
    private boolean mClientLost = true;
    private boolean mPlayingActive = false;

    public QuickEventsController(Context context) {
        mContext = context;
        initQuickEvents();
    }

    public void initQuickEvents() {
        mInitTimestamp = Utilities.getInitTimestamp(mContext);
        mIntroTimeout = mContext.getResources().getInteger(R.integer.config_quickSpaceIntroTimeout);
        registerPSAListener();
        updateQuickEvents();
    }

    private void registerPSAListener() {
        if (mRegistered) return;
        mRegistered = true;
        IntentFilter psonalityIntent = new IntentFilter();
        psonalityIntent.addAction(Intent.ACTION_TIME_TICK);
        psonalityIntent.addAction(Intent.ACTION_TIME_CHANGED);
        psonalityIntent.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        mContext.registerReceiver(mPSAListener, psonalityIntent);
    }

    private void unregisterPSAListener() {
        if (!mRegistered) return;
        mRegistered = false;
        mContext.unregisterReceiver(mPSAListener);
    }

    public void updateQuickEvents() {
        deviceIntroEvent();
        nowPlayingEvent();
        initNowPlayingEvent();
        psonalityEvent();
    }

    private void deviceIntroEvent() {
        if (!mRunning || isDeviceIntroCompleted()) return;

        mIsQuickEvent = true;
        mEventTitle = mContext.getResources().getString(R.string.quick_event_rom_intro_welcome);
        String[] intros = mContext.getResources().getStringArray(R.array.welcome_message_variants);
        mEventTitleSub = intros[getLuckyNumber(intros.length - 1)];
        mEventSubIcon = R.drawable.ic_quickspace_derp;

        mEventTitleSubAction = new OnClickListener() {
            @Override
            public void onClick(View view) {
                long forceComplete = Utilities.getInitTimestamp(mContext) - (mIntroTimeout * 60000);
                Utilities.setInitTimestamp(mContext, forceComplete);
                initQuickEvents();
            }
        };
    }

    public void nowPlayingEvent() {
        if (mEventNowPlaying) {
            boolean infoExpired = !mPlayingActive || mClientLost;
            if (infoExpired) {
                mIsQuickEvent = false;
                mEventNowPlaying = false;
            }
        }
    }

    public void initNowPlayingEvent() {
        if (!mRunning || !isDeviceIntroCompleted()) return;

        if (!Utilities.isQuickspaceNowPlaying(mContext)) return;

        if (!mPlayingActive) return;

        if (mNowPlayingTitle == null) return;

        mEventTitle = Utilities.showDateInPlaceOfNowPlaying(mContext) ?
                          Utilities.formatDateTime(mContext, System.currentTimeMillis()) :
                          mContext.getResources().getString(R.string.quick_event_ambient_now_playing);
        if (mNowPlayingArtist == null ) {
            mEventTitleSub = mNowPlayingTitle;
        } else {
            mEventTitleSub = String.format(mContext.getResources().getString(
                    R.string.quick_event_ambient_song_artist), mNowPlayingTitle, mNowPlayingArtist);
        }
        mEventSubIcon = R.drawable.ic_music_note_24dp;
        mIsQuickEvent = true;
        mEventNowPlaying = true;

        mEventTitleSubAction = new OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mPlayingActive) {
                    // Work required for local media actions
                    Intent npIntent = new Intent(Intent.ACTION_MAIN);
                    npIntent.addCategory(Intent.CATEGORY_APP_MUSIC);
                    npIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        Launcher.getLauncher(mContext).startActivitySafely(view, npIntent, null);
                    } catch (ActivityNotFoundException ex) {
                    }
                }
            }
        };
    }

    public void psonalityEvent() {
        if (!isDeviceIntroCompleted() || mEventNowPlaying) return;

        if (!Utilities.isQuickspacePersonalityEnabled(mContext)) return;

        mEventTitle = Utilities.formatDateTime(mContext, System.currentTimeMillis());
        mPSAMorningStr = mContext.getResources().getStringArray(R.array.quickspace_psa_morning);
        mPSAEvenStr = mContext.getResources().getStringArray(R.array.quickspace_psa_evening);
        mPSAMidniteStr = mContext.getResources().getStringArray(R.array.quickspace_psa_midnight);
        mPSARandomStr = mContext.getResources().getStringArray(R.array.quickspace_psa_random);
        int psaLength;

        // Clean the onClick event to avoid any weird behavior
        mEventTitleSubAction = new OnClickListener() {
            @Override
            public void onClick(View view) {
                // haha yes
            }
        };

        switch (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            case 5: case 6: case 7: case 8: case 9:
                psaLength = mPSAMorningStr.length - 1;
                mEventTitleSub = mPSAMorningStr[getLuckyNumber(0, psaLength)];
                mEventSubIcon = R.drawable.ic_quickspace_morning;
                mIsQuickEvent = true;
                break;

            case 19: case 20: case 21: case 22: case 23:
                psaLength = mPSAEvenStr.length - 1;
                mEventTitleSub = mPSAEvenStr[getLuckyNumber(0, psaLength)];
                mEventSubIcon = R.drawable.ic_quickspace_evening;
                mIsQuickEvent = true;
                break;

            case 0: case 1: case 2: case 3: case 4:
                psaLength = mPSAMidniteStr.length - 1;
                mEventTitleSub = mPSAMidniteStr[getLuckyNumber(0, psaLength)];
                mEventSubIcon = R.drawable.ic_quickspace_midnight;
                mIsQuickEvent = true;
                break;

            default:
                if (getLuckyNumber(13) == 7) {
                    psaLength = mPSARandomStr.length - 1;
                    mEventTitleSub = mPSARandomStr[getLuckyNumber(0, psaLength)];
                    mEventSubIcon = R.drawable.ic_quickspace_derp;
                    mIsQuickEvent = true;
                } else {
                    mIsQuickEvent = false;
                }
                break;
        }
    }

    public boolean isQuickEvent() {
        return mIsQuickEvent;
    }

    public boolean isDeviceIntroCompleted() {
        return ((System.currentTimeMillis() - mInitTimestamp) / 60000) > mIntroTimeout;
    }

    public String getTitle() {
        return mEventTitle;
    }

    public String getActionTitle() {
        return mEventTitleSub;
    }

    public OnClickListener getAction() {
        return mEventTitleSubAction;
    }

    public int getActionIcon() {
        return mEventSubIcon;
    }

    public int getLuckyNumber(int max) {
        return getLuckyNumber(0, max);
    }

    public int getLuckyNumber(int min, int max) {
        Random r = new Random();
        return r.nextInt((max - min) + 1) + min;
    }

    public void setMediaInfo(String title, String artist, boolean clientLost, boolean activePlayback) {
        mNowPlayingTitle = title;
        mNowPlayingArtist = artist;
        mClientLost = clientLost;
        mPlayingActive = activePlayback;
    }

    public void onPause() {
        mRunning = false;
        unregisterPSAListener();
    }

    public void onResume() {
        mRunning = true;
        registerPSAListener();
    }
}
