package com.android.launcher3.qsb;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import androidx.core.view.ViewCompat;
import com.android.launcher3.R;
import com.android.launcher3.Reorderable;
import com.android.launcher3.Utilities;
import com.android.launcher3.qsb.QsbContainerView;
import com.android.launcher3.util.MultiTranslateDelegate;
import com.android.launcher3.util.Themes;
import com.android.launcher3.LauncherPrefs;

public class QsbLayout extends FrameLayout implements Reorderable,
        SharedPreferences.OnSharedPreferenceChangeListener {

    ImageView mAssistantIcon;
    ImageView mGoogleIcon;
    ImageView mLensIcon;
    Context mContext;

    private final MultiTranslateDelegate mTranslateDelegate = new MultiTranslateDelegate(this);
    private float mScaleForReorderBounce = 1f;

    public QsbLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
    }

    public QsbLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mAssistantIcon = findViewById(R.id.mic_icon);
        mGoogleIcon = findViewById(R.id.g_icon);
        mLensIcon = findViewById(R.id.lens_icon);
        setIcons();

        LauncherPrefs.getPrefs(mContext).registerOnSharedPreferenceChangeListener(this);

        String searchPackage = QsbContainerView.getSearchWidgetPackageName(mContext);
        setOnClickListener(view -> {
            mContext.startActivity(new Intent("android.search.action.GLOBAL_SEARCH").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TASK).setPackage(searchPackage));
        });

        if (Utilities.isGSAEnabled(mContext)) {
            enableLensIcon();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        setMeasuredDimension(width, height);

        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (child != null) {
                measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
            }
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (key.equals(Themes.KEY_THEMED_ICONS)) {
            setIcons();
        }
    }

    private void setIcons() {
        if (Themes.isThemedIconEnabled(mContext)) {
            mAssistantIcon.setImageResource(R.drawable.ic_mic_themed);
            mGoogleIcon.setImageResource(R.drawable.ic_super_g_themed);
            mLensIcon.setImageResource(R.drawable.ic_lens_themed);
        } else {
            mAssistantIcon.setImageResource(R.drawable.ic_mic_color);
            mGoogleIcon.setImageResource(R.drawable.ic_super_g_color);
            mLensIcon.setImageResource(R.drawable.ic_lens_color);
        }
    }

    private void enableLensIcon() {
        mLensIcon.setVisibility(View.VISIBLE);
        mLensIcon.setOnClickListener(view -> {
            Intent lensIntent = new Intent();
            lensIntent.setAction(Intent.ACTION_VIEW)
                    .setComponent(new ComponentName(Utilities.GSA_PACKAGE, Utilities.LENS_ACTIVITY))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .setData(Uri.parse(Utilities.LENS_URI))
                    .putExtra("LensHomescreenShortcut", true);
            mContext.startActivity(lensIntent);
        });
    }

    @Override
    public MultiTranslateDelegate getTranslateDelegate() {
        return mTranslateDelegate;
    }

    @Override
    public void setReorderBounceScale(float scale) {
        mScaleForReorderBounce = scale;
        super.setScaleX(scale);
        super.setScaleY(scale);
    }

    @Override
    public float getReorderBounceScale() {
        return mScaleForReorderBounce;
    }

}
