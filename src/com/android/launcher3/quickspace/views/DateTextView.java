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
package com.android.launcher3.quickspace.views;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.icu.text.DisplayContext;
import android.text.format.DateUtils;
import android.util.AttributeSet;

import com.android.launcher3.R;
import com.android.launcher3.Utilities;

import java.util.Locale;

public class DateTextView extends DoubleShadowTextView {

    private final BroadcastReceiver mTimeChangeReceiver;
    private boolean mIsVisible = false;
    private boolean mIsEventMode = false;

    public DateTextView(final Context context) {
        this(context, null);
    }

    public DateTextView(final Context context, final AttributeSet set) {
        super(context, set, 0);
        mTimeChangeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                reloadDateFormat();
            }
        };
    }

    public void reloadDateFormat() {
        if (mIsEventMode) return;
        String formatted = Utilities.formatDateTime(getContext(), System.currentTimeMillis());
        setText(formatted);
        setContentDescription(formatted);
    }

    public void setEventMode(boolean isEventMode) {
        mIsEventMode = isEventMode;
    }

    private void registerReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_TIME_TICK);
        intentFilter.addAction(Intent.ACTION_TIME_CHANGED);
        intentFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        getContext().registerReceiver(mTimeChangeReceiver, intentFilter);
    }

    private void unregisterReceiver() {
        getContext().unregisterReceiver(mTimeChangeReceiver);
    }

    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (!mIsVisible && isVisible) {
            mIsVisible = true;
            registerReceiver();
            reloadDateFormat();
        } else if (mIsVisible && !isVisible) {
            unregisterReceiver();
            mIsVisible = false;
        }
    }
}
