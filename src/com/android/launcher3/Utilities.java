/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.launcher3;

import static com.android.launcher3.BuildConfig.WIDGET_ON_FIRST_SCREEN;
import static com.android.launcher3.Flags.enableSmartspaceAsAWidget;
import static com.android.launcher3.icons.BitmapInfo.FLAG_THEMED;
import static com.android.launcher3.util.SplitConfigurationOptions.STAGE_POSITION_BOTTOM_OR_RIGHT;
import static com.android.launcher3.util.SplitConfigurationOptions.STAGE_POSITION_TOP_OR_LEFT;
import static com.android.launcher3.util.SplitConfigurationOptions.STAGE_TYPE_MAIN;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.contextualsearch.ContextualSearchManager;
import android.app.KeyguardManager;
import android.app.Person;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LightingColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.BiometricManager.Authenticators;
import android.hardware.biometrics.BiometricPrompt;
import android.icu.text.DateFormat;
import android.icu.text.DisplayContext;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.CancellationSignal;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.TransactionTooLargeException;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.TtsSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.graphics.ColorUtils;

import com.android.launcher3.LauncherModel;
import com.android.launcher3.R;
import com.android.launcher3.dragndrop.FolderAdaptiveIcon;
import com.android.launcher3.graphics.TintedDrawableSpan;
import com.android.launcher3.icons.BitmapInfo;
import com.android.launcher3.icons.CacheableShortcutInfo;
import com.android.launcher3.icons.LauncherIcons;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.ItemInfoWithIcon;
import com.android.launcher3.pm.ShortcutConfigActivityInfo;
import com.android.launcher3.pm.UserCache;
import com.android.launcher3.shortcuts.ShortcutKey;
import com.android.launcher3.shortcuts.ShortcutRequest;
import com.android.launcher3.testing.shared.ResourceUtils;
import com.android.launcher3.util.FlagOp;
import com.android.launcher3.util.IntArray;
import com.android.launcher3.util.SplitConfigurationOptions.SplitPositionOption;
import com.android.launcher3.util.Themes;
import com.android.launcher3.views.ActivityContext;
import com.android.launcher3.views.BaseDragLayer;
import com.android.launcher3.widget.PendingAddShortcutInfo;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;

/**
 * Various utilities shared amongst the Launcher's classes.
 */
public final class Utilities {

    private static final String TAG = "Launcher.Utilities";

    private static final String TRIM_PATTERN = "(^\\h+|\\h+$)";

    private static final Matrix sMatrix = new Matrix();
    private static final Matrix sInverseMatrix = new Matrix();

    public static final String[] EMPTY_STRING_ARRAY = new String[0];
    public static final Person[] EMPTY_PERSON_ARRAY = new Person[0];

    @ChecksSdkIntAtLeast(api = VERSION_CODES.TIRAMISU, codename = "T")
    public static final boolean ATLEAST_T = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;

    @ChecksSdkIntAtLeast(api = VERSION_CODES.UPSIDE_DOWN_CAKE, codename = "U")
    public static final boolean ATLEAST_U = Build.VERSION.SDK_INT >= VERSION_CODES.UPSIDE_DOWN_CAKE;

    @ChecksSdkIntAtLeast(api = VERSION_CODES.VANILLA_ICE_CREAM, codename = "V")
    public static final boolean ATLEAST_V = Build.VERSION.SDK_INT
            >= VERSION_CODES.VANILLA_ICE_CREAM;

    private static final long WAIT_BEFORE_RESTART = 100; // ms

    public static final String KEY_LAUNCHER_INITIALIZED = "pref_launcher_initialized";
    public static final String DESKTOP_SHOW_QUICKSPACE = "pref_show_quickspace";
    public static final String KEY_SHOW_QUICKSPACE_NOWPLAYING = "pref_quickspace_np";
    public static final String KEY_SHOW_QUICKSPACE_NOWPLAYING_SHOWDATE = "pref_quickspace_np_showdate";
    public static final String KEY_SHOW_QUICKSPACE_PSONALITY = "pref_quickspace_psonality";

    /**
     * Set on a motion event dispatched from the nav bar. See {@link MotionEvent#setEdgeFlags(int)}.
     */
    public static final int EDGE_NAV_BAR = 1 << 8;

    /**
     * Indicates if the device has a debug build. Should only be used to store additional info or
     * add extra logging and not for changing the app behavior.
     * @deprecated Use {@link BuildConfig#IS_DEBUG_DEVICE} directly
     */
    @Deprecated
    public static final boolean IS_DEBUG_DEVICE = BuildConfig.IS_DEBUG_DEVICE;

    public static final int TRANSLATE_UP = 0;
    public static final int TRANSLATE_DOWN = 1;
    public static final int TRANSLATE_LEFT = 2;
    public static final int TRANSLATE_RIGHT = 3;

    public static final boolean SHOULD_SHOW_FIRST_PAGE_WIDGET =
            enableSmartspaceAsAWidget() && WIDGET_ON_FIRST_SCREEN;

    @IntDef({TRANSLATE_UP, TRANSLATE_DOWN, TRANSLATE_LEFT, TRANSLATE_RIGHT})
    public @interface AdjustmentDirection{}

    public static final String GSA_PACKAGE = "com.google.android.googlequicksearchbox";
    public static final String LENS_ACTIVITY = "com.google.android.apps.search.lens.LensExportedActivity";
    public static final String LENS_URI = "google://lens";
    public static final String LENS_SHARE_ACTIVITY = "com.google.android.apps.search.lens.LensShareEntryPointActivity";

    public static final String KEY_DOCK_SEARCH = "pref_dock_search";
    public static final String KEY_SHOW_HOTSEAT_BG = "pref_show_hotseat_bg";
    public static final String KEY_ALLOW_WALLPAPER_ZOOMING = "pref_allow_wallpaper_zooming";
    public static final String KEY_STATUS_BAR = "pref_show_statusbar";
    public static final String KEY_BLUR_DEPTH = "pref_blur_depth";
    public static final String KEY_RECENTS_OPACITY = "pref_recents_opacity";
    public static final String KEY_APP_DRAWER_OPACITY = "pref_app_drawer_opacity";
    public static final String KEY_RECENTS_MEMINFO = "pref_recents_meminfo";
    public static final String KEY_DRAWER_SEARCH = "pref_drawer_search";
    public static final String KEY_SHORT_PARALLAX = "pref_short_parallax";
    public static final String KEY_SINGLE_PAGE_CENTER = "pref_single_page_center";
    public static final String KEY_RECENTS_CHIPS = "pref_recents_chips";
    public static final String KEY_AUTO_KEYABORD = "pref_auto_keyboard";

    /**
     * Returns true if theme is dark.
     */
    public static boolean isDarkTheme(Context context) {
        Configuration configuration = context.getResources().getConfiguration();
        int nightMode = configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightMode == Configuration.UI_MODE_NIGHT_YES;
    }

    private static boolean sIsRunningInTestHarness = ActivityManager.isRunningInTestHarness();

    public static boolean isRunningInTestHarness() {
        return sIsRunningInTestHarness;
    }

    public static void enableRunningInTestHarnessForTests() {
        sIsRunningInTestHarness = true;
    }

    /** Disables running test in test harness mode */
    public static void disableRunningInTestHarnessForTests() {
        sIsRunningInTestHarness = false;
    }

    public static boolean isPropertyEnabled(String propertyName) {
        return Log.isLoggable(propertyName, Log.VERBOSE);
    }

    /**
     * Given a coordinate relative to the descendant, find the coordinate in a parent view's
     * coordinates.
     *
     * @param descendant The descendant to which the passed coordinate is relative.
     * @param ancestor The root view to make the coordinates relative to.
     * @param coord The coordinate that we want mapped.
     * @param includeRootScroll Whether or not to account for the scroll of the descendant:
     *          sometimes this is relevant as in a child's coordinates within the descendant.
     * @return The factor by which this descendant is scaled relative to this DragLayer. Caution
     *         this scale factor is assumed to be equal in X and Y, and so if at any point this
     *         assumption fails, we will need to return a pair of scale factors.
     */
    public static float getDescendantCoordRelativeToAncestor(
            View descendant, View ancestor, float[] coord, boolean includeRootScroll) {
        return getDescendantCoordRelativeToAncestor(descendant, ancestor, coord, includeRootScroll,
                false);
    }

    /**
     * Given a coordinate relative to the descendant, find the coordinate in a parent view's
     * coordinates.
     *
     * @param descendant The descendant to which the passed coordinate is relative.
     * @param ancestor The root view to make the coordinates relative to.
     * @param coord The coordinate that we want mapped.
     * @param includeRootScroll Whether or not to account for the scroll of the descendant:
     *          sometimes this is relevant as in a child's coordinates within the descendant.
     * @param ignoreTransform If true, view transform is ignored
     * @return The factor by which this descendant is scaled relative to this DragLayer. Caution
     *         this scale factor is assumed to be equal in X and Y, and so if at any point this
     *         assumption fails, we will need to return a pair of scale factors.
     */
    public static float getDescendantCoordRelativeToAncestor(View descendant, View ancestor,
            float[] coord, boolean includeRootScroll, boolean ignoreTransform) {
        float scale = 1.0f;
        View v = descendant;
        while(v != ancestor && v != null) {
            // For TextViews, scroll has a meaning which relates to the text position
            // which is very strange... ignore the scroll.
            if (v != descendant || includeRootScroll) {
                offsetPoints(coord, -v.getScrollX(), -v.getScrollY());
            }

            if (!ignoreTransform) {
                v.getMatrix().mapPoints(coord);
            }
            offsetPoints(coord, v.getLeft(), v.getTop());
            scale *= v.getScaleX();

            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return scale;
    }

    /**
     * Returns bounds for a child view of DragLayer, in drag layer coordinates.
     *
     * see {@link com.android.launcher3.dragndrop.DragLayer}.
     *
     * @param viewBounds Bounds of the view wanted in drag layer coordinates, relative to the view
     *                   itself. eg. (0, 0, view.getWidth, view.getHeight)
     * @param ignoreTransform If true, view transform is ignored
     * @param outRect The out rect where we return the bounds of {@param view} in drag layer coords.
     */
    public static void getBoundsForViewInDragLayer(BaseDragLayer dragLayer, View view,
            Rect viewBounds, boolean ignoreTransform, float[] recycle, RectF outRect) {
        float[] points = recycle == null ? new float[4] : recycle;
        points[0] = viewBounds.left;
        points[1] = viewBounds.top;
        points[2] = viewBounds.right;
        points[3] = viewBounds.bottom;

        Utilities.getDescendantCoordRelativeToAncestor(view, dragLayer, points,
                false, ignoreTransform);
        outRect.set(
                Math.min(points[0], points[2]),
                Math.min(points[1], points[3]),
                Math.max(points[0], points[2]),
                Math.max(points[1], points[3]));
    }

    /**
     * Similar to {@link #mapCoordInSelfToDescendant(View descendant, View root, float[] coord)}
     * but accepts a Rect instead of float[].
     */
    public static void mapRectInSelfToDescendant(View descendant, View root, Rect rect) {
        float[] coords = new float[]{rect.left, rect.top, rect.right, rect.bottom};
        mapCoordInSelfToDescendant(descendant, root, coords);
        rect.set((int) coords[0], (int) coords[1], (int) coords[2], (int) coords[3]);
    }

    /**
     * Inverse of {@link #getDescendantCoordRelativeToAncestor(View, View, float[], boolean)}.
     */
    public static void mapCoordInSelfToDescendant(View descendant, View root, float[] coord) {
        sMatrix.reset();
        View v = descendant;
        if (v != null) {
            while (v != root) {
                sMatrix.postTranslate(-v.getScrollX(), -v.getScrollY());
                sMatrix.postConcat(v.getMatrix());
                sMatrix.postTranslate(v.getLeft(), v.getTop());
                v = (View) v.getParent();
            }
            sMatrix.postTranslate(-v.getScrollX(), -v.getScrollY());
            sMatrix.invert(sInverseMatrix);
            sInverseMatrix.mapPoints(coord);
        }
    }

    /**
     * Sets {@param out} to be same as {@param in} by rounding individual values
     */
    public static void roundArray(float[] in, int[] out) {
        for (int i = 0; i < in.length; i++) {
            out[i] = Math.round(in[i]);
        }
    }

    public static void offsetPoints(float[] points, float offsetX, float offsetY) {
        for (int i = 0; i < points.length; i += 2) {
            points[i] += offsetX;
            points[i + 1] += offsetY;
        }
    }

    /**
     * Utility method to determine whether the given point, in local coordinates,
     * is inside the view, where the area of the view is expanded by the slop factor.
     * This method is called while processing touch-move events to determine if the event
     * is still within the view.
     */
    public static boolean pointInView(View v, float localX, float localY, float slop) {
        return localX >= -slop && localY >= -slop && localX < (v.getWidth() + slop) &&
                localY < (v.getHeight() + slop);
    }

    public static void scaleRectFAboutCenter(RectF r, float scale) {
        scaleRectFAboutCenter(r, scale, scale);
    }

    /**
     * Similar to {@link #scaleRectAboutCenter(Rect, float)} except this allows different scales
     * for X and Y
     */
    public static void scaleRectFAboutCenter(RectF r, float scaleX, float scaleY) {
        float px = r.centerX();
        float py = r.centerY();
        r.offset(-px, -py);
        r.left = r.left * scaleX;
        r.top = r.top * scaleY;
        r.right = r.right * scaleX;
        r.bottom = r.bottom * scaleY;
        r.offset(px, py);
    }

    public static void scaleRectAboutCenter(Rect r, float scale) {
        if (scale != 1.0f) {
            float cx = r.exactCenterX();
            float cy = r.exactCenterY();
            r.left = Math.round(cx + (r.left - cx) * scale);
            r.top = Math.round(cy + (r.top - cy) * scale);
            r.right = Math.round(cx + (r.right - cx) * scale);
            r.bottom = Math.round(cy + (r.bottom - cy) * scale);
        }
    }

    public static float shrinkRect(Rect r, float scaleX, float scaleY) {
        float scale = Math.min(Math.min(scaleX, scaleY), 1.0f);
        if (scale < 1.0f) {
            int deltaX = (int) (r.width() * (scaleX - scale) * 0.5f);
            r.left += deltaX;
            r.right -= deltaX;

            int deltaY = (int) (r.height() * (scaleY - scale) * 0.5f);
            r.top += deltaY;
            r.bottom -= deltaY;
        }
        return scale;
    }

    /**
     * Sets the x and y pivots for scaling from one Rect to another.
     *
     * @param src the source rectangle to scale from.
     * @param dst the destination rectangle to scale to.
     * @param outPivot the pivots set for scaling from src to dst.
     */
    public static void getPivotsForScalingRectToRect(Rect src, Rect dst, PointF outPivot) {
        float pivotXPct = ((float) src.left - dst.left) / ((float) dst.width() - src.width());
        outPivot.x = dst.left + dst.width() * pivotXPct;

        float pivotYPct = ((float) src.top - dst.top) / ((float) dst.height() - src.height());
        outPivot.y = dst.top + dst.height() * pivotYPct;
    }

    /**
     * Scales a {@code RectF} in place about a specified pivot point.
     *
     * <p>This method modifies the given {@code RectF} directly to scale it proportionally
     * by the given {@code scale}, while preserving its center at the specified
     * {@code (pivotX, pivotY)} coordinates.
     *
     * @param rectF the {@code RectF} to scale, modified directly.
     * @param pivotX the x-coordinate of the pivot point about which to scale.
     * @param pivotY the y-coordinate of the pivot point about which to scale.
     * @param scale the factor by which to scale the rectangle. Values less than 1 will
     *                    shrink the rectangle, while values greater than 1 will enlarge it.
     */
    public static void scaleRectFAboutPivot(RectF rectF, float pivotX, float pivotY, float scale) {
        rectF.offset(-pivotX, -pivotY);
        rectF.left *= scale;
        rectF.top *= scale;
        rectF.right *= scale;
        rectF.bottom *= scale;
        rectF.offset(pivotX, pivotY);
    }

    /**
     * Maps t from one range to another range.
     * @param t The value to map.
     * @param fromMin The lower bound of the range that t is being mapped from.
     * @param fromMax The upper bound of the range that t is being mapped from.
     * @param toMin The lower bound of the range that t is being mapped to.
     * @param toMax The upper bound of the range that t is being mapped to.
     * @return The mapped value of t.
     */
    public static float mapToRange(float t, float fromMin, float fromMax, float toMin, float toMax,
            Interpolator interpolator) {
        if (fromMin == fromMax || toMin == toMax) {
            Log.e(TAG, "mapToRange: range has 0 length");
            return toMin;
        }
        float progress = getProgress(t, fromMin, fromMax);
        return mapRange(interpolator.getInterpolation(progress), toMin, toMax);
    }

    /**
     * Maps t from one range to another range.
     * @param t The value to map.
     * @param fromMin The lower bound of the range that t is being mapped from.
     * @param fromMax The upper bound of the range that t is being mapped from.
     * @param toMin The lower bound of the range that t is being mapped to.
     * @param toMax The upper bound of the range that t is being mapped to.
     * @return The mapped value of t.
     */
    public static int mapToRange(int t, int fromMin, int fromMax, int toMin, int toMax,
            Interpolator interpolator) {
        if (fromMin == fromMax || toMin == toMax) {
            Log.e(TAG, "mapToRange: range has 0 length");
            return toMin;
        }
        float progress = getProgress(t, fromMin, fromMax);
        return (int) mapRange(interpolator.getInterpolation(progress), toMin, toMax);
    }

    /** Bounds t between a lower and upper bound and maps the result to a range. */
    public static float mapBoundToRange(float t, float lowerBound, float upperBound,
            float toMin, float toMax, Interpolator interpolator) {
        return mapToRange(boundToRange(t, lowerBound, upperBound), lowerBound, upperBound,
                toMin, toMax, interpolator);
    }

    public static float getProgress(float current, float min, float max) {
        return Math.abs(current - min) / Math.abs(max - min);
    }

    public static float mapRange(float value, float min, float max) {
        return min + (value * (max - min));
    }

    /**
     * Trims the string, removing all whitespace at the beginning and end of the string.
     * Non-breaking whitespaces are also removed.
     */
    @NonNull
    public static String trim(CharSequence s) {
        if (s == null) {
            return "";
        }
        return s.toString().replaceAll(TRIM_PATTERN, "").trim();
    }

    /**
     * Calculates the height of a given string at a specific text size.
     */
    public static int calculateTextHeight(float textSizePx) {
        Paint p = new Paint();
        p.setTextSize(textSizePx);
        Paint.FontMetrics fm = p.getFontMetrics();
        return (int) Math.ceil(fm.bottom - fm.top);
    }

    public static boolean isRtl(Resources res) {
        return res.getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    /** Converts a pixel value (px) to scale pixel value (SP) for the current device. */
    public static float pxToSp(float size) {
        return size / Resources.getSystem().getDisplayMetrics().scaledDensity;
    }

    public static float dpiFromPx(float size, int densityDpi) {
        float densityRatio = (float) densityDpi / DisplayMetrics.DENSITY_DEFAULT;
        return (size / densityRatio);
    }

    /** Converts a dp value to pixels for the current device. */
    public static int dpToPx(float dp) {
        return (int) (dp * Resources.getSystem().getDisplayMetrics().density);
    }

    /** Converts a dp value to pixels for a certain density. */
    public static int dpToPx(float dp, int densityDpi) {
        float densityRatio = (float) densityDpi / DisplayMetrics.DENSITY_DEFAULT;
        return (int) (dp * densityRatio);
    }

    public static int pxFromSp(float size, DisplayMetrics metrics) {
        return pxFromSp(size, metrics, 1f);
    }

    public static int pxFromSp(float size, DisplayMetrics metrics, float scale) {
        float value = scale * TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, metrics);
        return ResourceUtils.roundPxValueFromFloat(value);
    }

    public static String createDbSelectionQuery(String columnName, IntArray values) {
        return String.format(Locale.ENGLISH, "%s IN (%s)", columnName, values.toConcatString());
    }

    public static boolean isBootCompleted() {
        return "1".equals(getSystemProperty("sys.boot_completed", "1"));
    }

    public static String getSystemProperty(String property, String defaultValue) {
        try {
            Class clazz = Class.forName("android.os.SystemProperties");
            Method getter = clazz.getDeclaredMethod("get", String.class);
            String value = (String) getter.invoke(null, property);
            if (!TextUtils.isEmpty(value)) {
                return value;
            }
        } catch (Exception e) {
            Log.d(TAG, "Unable to read system properties");
        }
        return defaultValue;
    }

    /**
     * Ensures that a value is within given bounds. Specifically:
     * If value is less than lowerBound, return lowerBound; else if value is greater than upperBound,
     * return upperBound; else return value unchanged.
     */
    public static int boundToRange(int value, int lowerBound, int upperBound) {
        return Math.max(lowerBound, Math.min(value, upperBound));
    }

    /**
     * @see #boundToRange(int, int, int).
     */
    public static float boundToRange(float value, float lowerBound, float upperBound) {
        return Math.max(lowerBound, Math.min(value, upperBound));
    }

    /**
     * @see #boundToRange(int, int, int).
     */
    public static long boundToRange(long value, long lowerBound, long upperBound) {
        return Math.max(lowerBound, Math.min(value, upperBound));
    }

    /**
     * Wraps a message with a TTS span, so that a different message is spoken than
     * what is getting displayed.
     * @param msg original message
     * @param ttsMsg message to be spoken
     */
    public static CharSequence wrapForTts(CharSequence msg, String ttsMsg) {
        SpannableString spanned = new SpannableString(msg);
        spanned.setSpan(new TtsSpan.TextBuilder(ttsMsg).build(),
                0, spanned.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
        return spanned;
    }

    /**
     * Prefixes a text with the provided icon
     */
    public static CharSequence prefixTextWithIcon(Context context, int iconRes, CharSequence msg) {
        // Update the hint to contain the icon.
        // Prefix the original hint with two spaces. The first space gets replaced by the icon
        // using span. The second space is used for a singe space character between the hint
        // and the icon.
        SpannableString spanned = new SpannableString("  " + msg);
        spanned.setSpan(new TintedDrawableSpan(context, iconRes),
                0, 1, Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
        return spanned;
    }

    public static boolean isWallpaperSupported(Context context) {
        return context.getSystemService(WallpaperManager.class).isWallpaperSupported();
    }

    public static boolean isWallpaperAllowed(Context context) {
        return context.getSystemService(WallpaperManager.class).isSetWallpaperAllowed();
    }

    public static boolean isBinderSizeError(Exception e) {
        return e.getCause() instanceof TransactionTooLargeException
                || e.getCause() instanceof DeadObjectException;
    }

    /**
     * Utility method to post a runnable on the handler, skipping the synchronization barriers.
     */
    public static void postAsyncCallback(Handler handler, Runnable callback) {
        Message msg = Message.obtain(handler, callback);
        msg.setAsynchronous(true);
        handler.sendMessage(msg);
    }

    /**
     * Utility method to allow background activity launch for the provided activity options
     */
    public static ActivityOptions allowBGLaunch(ActivityOptions options) {
        if (ATLEAST_U) {
            options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        }
        return options;
    }

    /**
     * Utility method to know if a device's primary language is English.
     */
    public static boolean isEnglishLanguage(Context context) {
        return context.getResources().getConfiguration().locale.getLanguage()
                .equals(Locale.ENGLISH.getLanguage());
    }

    /**
     * Returns the full drawable for info as multiple layers of AdaptiveIconDrawable. The second
     * drawable in the Pair is the badge used with the icon.
     *
     * @param useTheme If true, will theme icons when applicable
     */
    @SuppressLint("UseCompatLoadingForDrawables")
    @Nullable
    @WorkerThread
    public static <T extends Context & ActivityContext> Pair<AdaptiveIconDrawable, Drawable>
            getFullDrawable(T context, ItemInfo info, int width, int height, boolean useTheme) {
        useTheme &= Themes.isThemedIconEnabled(context);
        LauncherAppState appState = LauncherAppState.getInstance(context);
        Drawable mainIcon = null;

        Drawable badge = null;
        if ((info instanceof ItemInfoWithIcon iiwi) && !iiwi.usingLowResIcon()) {
            try (LauncherIcons li = LauncherIcons.obtain(context)) {
                badge = iiwi.bitmap.withUser(iiwi.user, li).getBadgeDrawable(context, useTheme);
            }
        }

        if (info instanceof PendingAddShortcutInfo) {
            ShortcutConfigActivityInfo activityInfo =
                    ((PendingAddShortcutInfo) info).getActivityInfo(context);
            mainIcon = activityInfo.getFullResIcon(appState.getIconCache());
        } else if (info.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPLICATION) {
            android.content.Intent intent = info.getIntent();
            if (intent == null) {
                return null;
            }
            LauncherActivityInfo activityInfo = context.getSystemService(LauncherApps.class)
                    .resolveActivity(intent, info.user);
            if (activityInfo == null) {
                return null;
            }
            mainIcon = appState.getIconCache().getFullResIcon(activityInfo.getActivityInfo());
        } else if (info.itemType == LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT) {
            List<ShortcutInfo> siList = ShortcutKey.fromItemInfo(info)
                    .buildRequest(context)
                    .query(ShortcutRequest.ALL);
            if (siList.isEmpty()) {
                return null;
            } else {
                ShortcutInfo si = siList.get(0);
                mainIcon = CacheableShortcutInfo.getIcon(context, si,
                        appState.getInvariantDeviceProfile().fillResIconDpi);
                // Only fetch badge if the icon is on workspace
                if (info.id != ItemInfo.NO_ID && badge == null) {
                    badge = appState.getIconCache().getShortcutInfoBadge(si)
                            .newIcon(context, FLAG_THEMED);
                }
            }
        } else if (info.itemType == LauncherSettings.Favorites.ITEM_TYPE_FOLDER) {
            FolderAdaptiveIcon icon = FolderAdaptiveIcon.createFolderAdaptiveIcon(
                    context, info.id, new Point(width, height));
            if (icon == null) {
                return null;
            }
            mainIcon =  icon;
            badge = icon.getBadge();
        }

        if (mainIcon == null) {
            return null;
        }
        AdaptiveIconDrawable result;
        if (mainIcon instanceof AdaptiveIconDrawable aid) {
            result = aid;
        } else {
            // Wrap the main icon in AID
            try (LauncherIcons li = LauncherIcons.obtain(context)) {
                result = li.wrapToAdaptiveIcon(mainIcon, null);
            }
        }
        if (result == null) {
            return null;
        }

        // Inject theme icon drawable
        if (ATLEAST_T && useTheme) {
            try (LauncherIcons li = LauncherIcons.obtain(context)) {
                if (li.getThemeController() != null) {
                    AdaptiveIconDrawable themed = li.getThemeController().createThemedAdaptiveIcon(
                            context,
                            result,
                            info instanceof ItemInfoWithIcon iiwi ? iiwi.bitmap : null);
                    if (themed != null) {
                        result = themed;
                    }
                }
            }
        }

        if (badge == null) {
            try (LauncherIcons li = LauncherIcons.obtain(context)) {
                badge = BitmapInfo.LOW_RES_INFO.withUser(info.user, li).withFlags(
                                UserCache.INSTANCE.get(context)
                                        .getUserInfo(info.user)
                                        .applyBitmapInfoFlags(FlagOp.NO_OP))
                        .getBadgeDrawable(context, useTheme);
            }
            if (badge == null) {
                badge = new ColorDrawable(Color.TRANSPARENT);
            }
        }
        return Pair.create(result, badge);
    }

    public static float squaredHypot(float x, float y) {
        return x * x + y * y;
    }

    public static float squaredTouchSlop(Context context) {
        float slop = ViewConfiguration.get(context).getScaledTouchSlop();
        return slop * slop;
    }

    /**
     * Rotates `inOutBounds` by `delta` 90-degree increments. Rotation is visually CW. Parent
     * sizes represent the "space" that will rotate carrying inOutBounds along with it to determine
     * the final bounds.
     *
     * As an example if this is the input:
     * +-------------+
     * |   +-----+   |
     * |   |     |   |
     * |   +-----+   |
     * |             |
     * |             |
     * |             |
     * +-------------+
     * This would be case delta % 4 == 0:
     * +-------------+
     * |   +-----+   |
     * |   |     |   |
     * |   +-----+   |
     * |             |
     * |             |
     * |             |
     * +-------------+
     * This would be case delta % 4 == 1:
     * +----------------+
     * |          +--+  |
     * |          |  |  |
     * |          |  |  |
     * |          +--+  |
     * |                |
     * +----------------+
     * This would be case delta % 4 == 2: // This is case was reverted to previous behaviour which
     * doesn't match the illustration due to b/353965234
     * +-------------+
     * |             |
     * |             |
     * |             |
     * |   +-----+   |
     * |   |     |   |
     * |   +-----+   |
     * +-------------+
     * This would be case delta % 4 == 3:
     * +----------------+
     * |  +--+          |
     * |  |  |          |
     * |  |  |          |
     * |  +--+          |
     * |                |
     * +----------------+
     */
    public static void rotateBounds(Rect inOutBounds, int parentWidth, int parentHeight,
            int delta) {
        int rdelta = ((delta % 4) + 4) % 4;
        int origLeft = inOutBounds.left;
        switch (rdelta) {
            case 0:
                return;
            case 1:
                inOutBounds.left = inOutBounds.top;
                inOutBounds.top = parentWidth - inOutBounds.right;
                inOutBounds.right = inOutBounds.bottom;
                inOutBounds.bottom = parentWidth - origLeft;
                return;
            case 2:
                inOutBounds.left = parentWidth - inOutBounds.right;
                inOutBounds.right = parentWidth - origLeft;
                return;
            case 3:
                inOutBounds.left = parentHeight - inOutBounds.bottom;
                inOutBounds.bottom = inOutBounds.right;
                inOutBounds.right = parentHeight - inOutBounds.top;
                inOutBounds.top = origLeft;
                return;
        }
    }

    /**
     * Make a color filter that blends a color into the destination based on a scalable amout.
     *
     * @param color to blend in.
     * @param tintAmount [0-1] 0 no tinting, 1 full color.
     * @return ColorFilter for tinting, or {@code null} if no filter is needed.
     */
    public static ColorFilter makeColorTintingColorFilter(int color, float tintAmount) {
        if (tintAmount == 0f) {
            return null;
        }
        return new LightingColorFilter(
                // This isn't blending in white, its making a multiplication mask for the base color
                ColorUtils.blendARGB(Color.WHITE, 0, tintAmount),
                ColorUtils.blendARGB(0, color, tintAmount));
    }

    public static Rect getViewBounds(@NonNull View v) {
        int[] pos = new int[2];
        v.getLocationOnScreen(pos);
        return new Rect(pos[0], pos[1], pos[0] + v.getWidth(), pos[1] + v.getHeight());
    }

    /**
     * Returns a list of screen-splitting options depending on the device orientation (split top for
     * portrait, split right for landscape)
     */
    public static List<SplitPositionOption> getSplitPositionOptions(
            DeviceProfile dp) {
        int splitIconRes = dp.isLeftRightSplit
                ? R.drawable.ic_split_horizontal
                : R.drawable.ic_split_vertical;
        int stagePosition = dp.isLeftRightSplit
                ? STAGE_POSITION_BOTTOM_OR_RIGHT
                : STAGE_POSITION_TOP_OR_LEFT;
        return Collections.singletonList(new SplitPositionOption(
                splitIconRes,
                R.string.recent_task_option_split_screen,
                stagePosition,
                STAGE_TYPE_MAIN
        ));
    }

    /** Logs the Scale and Translate properties of a matrix. Ignores skew and perspective. */
    public static void logMatrix(String label, Matrix matrix) {
        float[] matrixValues = new float[9];
        matrix.getValues(matrixValues);
        Log.d(label, String.format("%s: %s\nscale (x,y) = (%f, %f)\ntranslate (x,y) = (%f, %f)",
                label, matrix, matrixValues[Matrix.MSCALE_X], matrixValues[Matrix.MSCALE_Y],
                matrixValues[Matrix.MTRANS_X], matrixValues[Matrix.MTRANS_Y]
        ));
    }

    /**
     * Translates the {@code targetView} so that it overlaps with {@code exclusionBounds} as little
     * as possible, while remaining within {@code inclusionBounds}.
     * <p>
     * {@code inclusionBounds} will always take precedence over {@code exclusionBounds}, so if
     * {@code targetView} needs to be translated outside of {@code inclusionBounds} to fully fix an
     * overlap with {@code exclusionBounds}, then {@code targetView} will only be translated up to
     * the border of {@code inclusionBounds}.
     * <p>
     * Note: {@code targetViewBounds}, {@code inclusionBounds} and {@code exclusionBounds} must all
     * be in relation to the same reference point on screen.
     * <p>
     * @param targetView the view being translated
     * @param targetViewBounds the bounds of the {@code targetView}
     * @param inclusionBounds the bounds the {@code targetView} absolutely must stay within
     * @param exclusionBounds the bounds to try to move the {@code targetView} away from
     * @param adjustmentDirection the translation direction that should be attempted to fix an
     *                            overlap
     */
    public static void translateOverlappingView(
            @NonNull View targetView,
            @NonNull Rect targetViewBounds,
            @NonNull Rect inclusionBounds,
            @NonNull Rect exclusionBounds,
            @AdjustmentDirection int adjustmentDirection) {
        if (!Rect.intersects(targetViewBounds, exclusionBounds)) {
            return;
        }
        switch (adjustmentDirection) {
            case TRANSLATE_RIGHT:
                targetView.setTranslationX(Math.min(
                        // Translate to the right if the view is overlapping on the left.
                        Math.max(0, exclusionBounds.right - targetViewBounds.left),
                        // Do not translate beyond the inclusion bounds.
                        inclusionBounds.right - targetViewBounds.right));
                break;
            case TRANSLATE_LEFT:
                targetView.setTranslationX(Math.max(
                        // Translate to the left if the view is overlapping on the right.
                        Math.min(0, exclusionBounds.left - targetViewBounds.right),
                        // Do not translate beyond the inclusion bounds.
                        inclusionBounds.left - targetViewBounds.left));
                break;
            case TRANSLATE_DOWN:
                targetView.setTranslationY(Math.min(
                        // Translate downwards if the view is overlapping on the top.
                        Math.max(0, exclusionBounds.bottom - targetViewBounds.top),
                        // Do not translate beyond the inclusion bounds.
                        inclusionBounds.bottom - targetViewBounds.bottom));
                break;
            case TRANSLATE_UP:
                targetView.setTranslationY(Math.max(
                        // Translate upwards if the view is overlapping on the bottom.
                        Math.min(0, exclusionBounds.top - targetViewBounds.bottom),
                        // Do not translate beyond the inclusion bounds.
                        inclusionBounds.top - targetViewBounds.top));
                break;
            default:
                // No-Op
        }
    }

    /**
     * Does a depth-first search through the View hierarchy starting at root, to find a view that
     * matches the predicate. Returns null if no View was found. View has a findViewByPredicate
     * member function but it is currently a @hide API.
     */
    @Nullable
    public static <T extends View> T findViewByPredicate(@NonNull View root,
            @NonNull Predicate<View> predicate) {
        if (predicate.test(root)) {
            return (T) root;
        }
        if (root instanceof ViewGroup parent) {
            int count = parent.getChildCount();
            for (int i = 0; i < count; i++) {
                View view = findViewByPredicate(parent.getChildAt(i), predicate);
                if (view != null) {
                    return (T) view;
                }
            }
        }
        return null;
    }

    public static void restart() {
        MAIN_EXECUTOR.getHandler().postDelayed(() -> {
            System.exit(0);
        }, WAIT_BEFORE_RESTART);
    }

    public static boolean isWorkspaceEditAllowed(Context context) {
        SharedPreferences prefs = LauncherPrefs.getPrefs(context.getApplicationContext());
        return !prefs.getBoolean(InvariantDeviceProfile.KEY_WORKSPACE_LOCK, false);
    }

    public static boolean isGSAEnabled(Context context) {
        try {
            return context.getPackageManager().getApplicationInfo(GSA_PACKAGE, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Shows authentication screen to confirm credentials (pin, pattern or password) for the current
     * user of the device.
     *
     * @param context The {@code Context} used to get {@code KeyguardManager} service
     * @param title the {@code String} which will be shown as the pompt title
     * @param successRunnable The {@code Runnable} which will be executed if the user does not setup
     *                        device security or if lock screen is unlocked
     */
    public static void showLockScreen(Context context, String title, Runnable successRunnable) {
        if (hasSecureKeyguard(context)) {
            final BiometricPrompt.AuthenticationCallback authenticationCallback =
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                    BiometricPrompt.AuthenticationResult result) {
                            successRunnable.run();
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            //Do nothing
                        }
            };

            final BiometricPrompt bp = new BiometricPrompt.Builder(context)
                    .setTitle(title)
                    .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG |
                                              Authenticators.DEVICE_CREDENTIAL)
                    .build();

            final Handler handler = new Handler(Looper.getMainLooper());
            bp.authenticate(new CancellationSignal(),
                    runnable -> handler.post(runnable),
                    authenticationCallback);
        } else {
            // Notify the user a secure keyguard is required for protected apps,
            // but allow to set hidden apps
            Toast.makeText(context, R.string.trust_apps_no_lock_error, Toast.LENGTH_LONG)
                .show();
            successRunnable.run();
        }
    }

    public static String formatDateTime(Context context, long timeInMillis) {
        try {
            String format = context.getString(R.string.abbrev_wday_month_day_no_year);
            String formattedDate;
            DateFormat dateFormat = DateFormat.getInstanceForSkeleton(format, Locale.getDefault());
            dateFormat.setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE);
            formattedDate = dateFormat.format(timeInMillis);
            return formattedDate;
        } catch (Throwable t) {
            Log.e(TAG, "Error formatting At A Glance date", t);
            return DateUtils.formatDateTime(context, timeInMillis, DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH);
        }
    }

    public static Bitmap addShadowToBitmap(Bitmap bmp, float rad, int alpha) {
        int size = dpToPx(rad) + bmp.getWidth();
        Paint blurPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        blurPaint.setMaskFilter(new BlurMaskFilter(dpToPx(rad), BlurMaskFilter.Blur.NORMAL));
        Paint drawPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        int[] offset = new int[2];
        Bitmap shadow = bmp.extractAlpha(blurPaint, offset);
        Bitmap result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas();
        canvas.setBitmap(result);

        drawPaint.setAlpha(alpha);
        canvas.drawBitmap(shadow, offset[0], offset[1], drawPaint);
        canvas.drawBitmap(shadow, offset[0], offset[1], drawPaint);

        drawPaint.setAlpha(255);
        canvas.drawBitmap(bmp, 0, 0, drawPaint);
        canvas.setBitmap(null);
        return result;
    }

    public static ImageView addShadowToImageView(ImageView view, float rad, int alpha) {
        Drawable drawable = view.getDrawable();
        if (drawable == null) return view;
        Bitmap src;
        if (drawable instanceof BitmapDrawable) {
            src = ((BitmapDrawable) drawable).getBitmap();
        } else {
            src = Bitmap.createBitmap(drawable.getIntrinsicWidth(),
                    drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(src);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
        }

        Bitmap shadowedBitmap = addShadowToBitmap(src, rad, alpha);
        if (view.getImageTintList() != null) {
            // disable tint as it can affect generated shadow
            view.setImageTintList(null);
        }
        view.setImageBitmap(shadowedBitmap);
        return view;
    }

    public static boolean hasSecureKeyguard(Context context) {
        final KeyguardManager keyguardManager = context.getSystemService(KeyguardManager.class);
        return keyguardManager != null && keyguardManager.isKeyguardSecure();
    }

    public static boolean showQSB(Context context) {
        return isGSAEnabled(context) && isQSBEnabled(context);
    }

    private static boolean isQSBEnabled(Context context) {
        SharedPreferences prefs = LauncherPrefs.getPrefs(context.getApplicationContext());
        return prefs.getBoolean(KEY_DOCK_SEARCH, true);
    }

    public static boolean isHotseatBgEnabled(Context context) {
        SharedPreferences prefs = LauncherPrefs.getPrefs(context.getApplicationContext());
        return prefs.getBoolean(KEY_SHOW_HOTSEAT_BG, false);
    }

    public static boolean showDateInPlaceOfNowPlaying(Context context) {
        SharedPreferences prefs = LauncherPrefs.getPrefs(context.getApplicationContext());
        return prefs.getBoolean(KEY_SHOW_QUICKSPACE_NOWPLAYING_SHOWDATE, true);
    }

    public static boolean isQuickspacePersonalityEnabled(Context context) {
        SharedPreferences prefs = LauncherPrefs.getPrefs(context.getApplicationContext());
        return prefs.getBoolean(KEY_SHOW_QUICKSPACE_PSONALITY, true);
    }

    public static void setInitTimestamp(Context context, long time) {
        SharedPreferences prefs = LauncherPrefs.getPrefs(context.getApplicationContext());
        prefs.edit().putLong(KEY_LAUNCHER_INITIALIZED, time).apply();
    }

    public static long getInitTimestamp(Context context) {
        SharedPreferences prefs = LauncherPrefs.getPrefs(context.getApplicationContext());
        return prefs.getLong(KEY_LAUNCHER_INITIALIZED, 0);
    }

    public static boolean canZoomWallpaper(Context context) {
        SharedPreferences prefs = LauncherPrefs.getPrefs(context.getApplicationContext());
        return prefs.getBoolean(KEY_ALLOW_WALLPAPER_ZOOMING, true);
    }

    public static boolean showStatusbarEnabled(Context context) {
        SharedPreferences prefs = LauncherPrefs.getPrefs(context.getApplicationContext());
        return prefs.getBoolean(KEY_STATUS_BAR, true);
    }

    public static int getBlurRadius(Context context) {
        SharedPreferences prefs = LauncherPrefs.getPrefs(context.getApplicationContext());
        return prefs.getInt(KEY_BLUR_DEPTH, 0);
    }

    public static int getRecentsOpacity(Context context) {
        SharedPreferences prefs = LauncherPrefs.getPrefs(context.getApplicationContext());
        return prefs.getInt(KEY_RECENTS_OPACITY, 100);
    }

    public static int getAllAppsOpacity(Context context) {
        SharedPreferences prefs = LauncherPrefs.getPrefs(context.getApplicationContext());
        return prefs.getInt(KEY_APP_DRAWER_OPACITY, 100);
    }

    public static boolean isShowMeminfo(Context context) {
        SharedPreferences prefs = LauncherPrefs.getPrefs(context.getApplicationContext());
        return prefs.getBoolean(KEY_RECENTS_MEMINFO, false);
    }

    public static boolean showSearch(Context context) {
        SharedPreferences prefs = LauncherPrefs.getPrefs(context.getApplicationContext());
        return prefs.getBoolean(KEY_DRAWER_SEARCH, true);
    }

    public static boolean isShortParallax(Context context) {
        SharedPreferences prefs = LauncherPrefs.getPrefs(context.getApplicationContext());
        return prefs.getBoolean(KEY_SHORT_PARALLAX, false);
    }

    public static boolean isSinglePageCentered(Context context) {
        SharedPreferences prefs = LauncherPrefs.getPrefs(context.getApplicationContext());
        return prefs.getBoolean(KEY_SINGLE_PAGE_CENTER, false);
    }

    public static boolean enableAutoIme(Context context) {
        SharedPreferences prefs = LauncherPrefs.getPrefs(context.getApplicationContext());
        return prefs.getBoolean(KEY_AUTO_KEYABORD, false);
    }

    public static boolean showQuickspace(Context context) {
        SharedPreferences prefs = LauncherPrefs.getPrefs(context.getApplicationContext());
        return prefs.getBoolean(DESKTOP_SHOW_QUICKSPACE, true);
    }

    public static boolean isQuickspaceNowPlaying(Context context) {
        SharedPreferences prefs = LauncherPrefs.getPrefs(context.getApplicationContext());
        return prefs.getBoolean(KEY_SHOW_QUICKSPACE_NOWPLAYING, true);
    }

    public static int getSeraphixHolderId(Context context) {
        SharedPreferences prefs = LauncherPrefs.getPrefs(context.getApplicationContext());
        return prefs.getInt("seraphix_holder_id", -1);
    }

    public static void setSeraphixHolderId(Context context, int newId) {
        SharedPreferences prefs = LauncherPrefs.getPrefs(context.getApplicationContext());
        prefs.edit().putInt("seraphix_holder_id", newId).apply();
    }

    public static boolean startContextualSearch(Context context, int entrypoint) {
        Context appContext = context.getApplicationContext();
        ContextualSearchManager contextualSearchManager = 
            (ContextualSearchManager) appContext.getSystemService(Context.CONTEXTUAL_SEARCH_SERVICE);
        if (contextualSearchManager == null || !isLongPressSearchEnabled(context)) {
            return false;
        }
        try {
            contextualSearchManager.startContextualSearch(entrypoint);
            return true;
        } catch (Exception e) {}
        return false;
    }
    
    public static boolean isLongPressSearchEnabled(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.SEARCH_ALL_ENTRYPOINTS_ENABLED, 1)
                == 1;
    }
}
