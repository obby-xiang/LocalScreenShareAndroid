package com.obby.android.localscreenshare.support;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PointF;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.obby.android.localscreenshare.App;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@SuppressLint("ApplySharedPref")
public final class Preferences {
    public static final String KEY_SERVER_PORT = "server_port";

    public static final String KEY_PROJECTION_SECURE = "projection_secure";

    public static final String KEY_PROJECTION_SCALE = "projection_scale";

    public static final String KEY_PROJECTION_KEEP_SCREEN_ON = "projection_keep_screen_on";

    public static final String KEY_PROJECTION_FRAME_RATE = "projection_frame_rate";

    public static final String KEY_PROJECTION_QUALITY = "projection_quality";

    public static final String KEY_SERVICE_CHIP_LOCATION = "service_chip_location";

    public static final String KEY_VIEWER_KEEP_SCREEN_ON = "viewer_keep_screen_on";

    public static final String KEY_VIEWER_OPACITY = "viewer_opacity";

    public static final String KEY_VIEWER_SCALE = "viewer_scale";

    public static final String KEY_VIEWER_LOCATION = "viewer_location";

    public static final String KEY_VIEWER_ROUNDED = "viewer_rounded";

    public static final String KEY_DISCOVERY_ENABLED = "discovery_enabled";

    private static final String PREF_FILE_NAME = "lss-preferences";

    private static final String KEY_SERVICE_ID = "service_id";

    private static final String KEY_SERVICE_NAME = "service_name";

    private static final int DEFAULT_SERVER_PORT = 8080;

    private static final int DEFAULT_PROJECTION_SCALE = 100;

    private static final int DEFAULT_PROJECTION_QUALITY = 85;

    private static final int DEFAULT_PROJECTION_FRAME_RATE = 60;

    private static final PointF DEFAULT_SERVICE_CHIP_LOCATION = new PointF(0f, 0f);

    private static final int DEFAULT_VIEWER_OPACITY = 100;

    private static final int DEFAULT_VIEWER_SCALE = 80;

    private static final PointF DEFAULT_VIEWER_LOCATION = new PointF(0.5f, 0.5f);

    @NonNull
    private final SharedPreferences mPreferences;

    @NonNull
    private final Gson mGson;

    @NonNull
    private final List<Observer> mObservers = new CopyOnWriteArrayList<>();

    @NonNull
    private final SharedPreferences.OnSharedPreferenceChangeListener mOnSharedPreferenceChangeListener =
        (sharedPreferences, key) -> mObservers.forEach(observer -> observer.onChanged(key));

    private Preferences() {
        mPreferences = App.get().getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE);
        mGson = new Gson();
    }

    @NonNull
    public static Preferences get() {
        return InstanceHolder.INSTANCE;
    }

    public void addObserver(@NonNull final Observer observer) {
        if (mObservers.contains(observer)) {
            return;
        }

        if (mObservers.isEmpty()) {
            mPreferences.registerOnSharedPreferenceChangeListener(mOnSharedPreferenceChangeListener);
        }

        mObservers.add(observer);
    }

    public void removeObserver(@NonNull final Observer observer) {
        mObservers.remove(observer);

        if (mObservers.isEmpty()) {
            mPreferences.unregisterOnSharedPreferenceChangeListener(mOnSharedPreferenceChangeListener);
        }
    }

    @NonNull
    public String getServiceId() {
        return Optional.ofNullable(mPreferences.getString(KEY_SERVICE_ID, null))
            .orElseGet(() -> {
                final String serviceId = UUID.randomUUID().toString().replace("-", "");
                mPreferences.edit().putString(KEY_SERVICE_ID, serviceId).commit();
                return serviceId;
            });
    }

    @NonNull
    public String getServiceName() {
        return Optional.ofNullable(mPreferences.getString(KEY_SERVICE_NAME, null))
            .orElseGet(() -> {
                final String serviceName =
                    Settings.Global.getString(App.get().getContentResolver(), Settings.Global.DEVICE_NAME);
                mPreferences.edit().putString(KEY_SERVICE_NAME, serviceName).commit();
                return serviceName;
            });
    }

    public int getServerPort() {
        return mPreferences.getInt(KEY_SERVER_PORT, DEFAULT_SERVER_PORT);
    }

    public void setServerPort(final int port) {
        mPreferences.edit().putInt(KEY_SERVER_PORT, port).commit();
    }

    public boolean isProjectionSecure() {
        return mPreferences.getBoolean(KEY_PROJECTION_SECURE, false);
    }

    public void setProjectionSecure(final boolean isSecure) {
        mPreferences.edit().putBoolean(KEY_PROJECTION_SECURE, isSecure).commit();
    }

    public boolean isProjectionKeepScreenOn() {
        return mPreferences.getBoolean(KEY_PROJECTION_KEEP_SCREEN_ON, true);
    }

    public void setProjectionKeepScreenOn(final boolean isKeepScreenOn) {
        mPreferences.edit().putBoolean(KEY_PROJECTION_KEEP_SCREEN_ON, isKeepScreenOn).commit();
    }

    public int getProjectionFrameRate() {
        return mPreferences.getInt(KEY_PROJECTION_FRAME_RATE, DEFAULT_PROJECTION_FRAME_RATE);
    }

    public void setProjectionFrameRate(final int frameRate) {
        mPreferences.edit().putInt(KEY_PROJECTION_FRAME_RATE, frameRate).commit();
    }

    public int getProjectionQuality() {
        return mPreferences.getInt(KEY_PROJECTION_QUALITY, DEFAULT_PROJECTION_QUALITY);
    }

    public void setProjectionQuality(final int quality) {
        mPreferences.edit().putInt(KEY_PROJECTION_QUALITY, quality).commit();
    }

    public int getProjectionScale() {
        return mPreferences.getInt(KEY_PROJECTION_SCALE, DEFAULT_PROJECTION_SCALE);
    }

    public void setProjectionScale(final int scale) {
        mPreferences.edit().putInt(KEY_PROJECTION_SCALE, scale).commit();
    }

    @NonNull
    public PointF getServiceChipLocation() {
        return Optional.ofNullable(mPreferences.getString(KEY_SERVICE_CHIP_LOCATION, null))
            .map(value -> mGson.fromJson(value, PointF.class))
            .orElseGet(() -> new PointF(DEFAULT_SERVICE_CHIP_LOCATION.x, DEFAULT_SERVICE_CHIP_LOCATION.y));
    }

    public void setServiceChipLocation(@NonNull final PointF location) {
        mPreferences.edit().putString(KEY_SERVICE_CHIP_LOCATION, mGson.toJson(location)).commit();
    }

    public boolean isViewerKeepScreenOn() {
        return mPreferences.getBoolean(KEY_VIEWER_KEEP_SCREEN_ON, true);
    }

    public void setViewerKeepScreenOn(final boolean isKeepScreenOn) {
        mPreferences.edit().putBoolean(KEY_VIEWER_KEEP_SCREEN_ON, isKeepScreenOn).commit();
    }

    public int getViewerOpacity() {
        return mPreferences.getInt(KEY_VIEWER_OPACITY, DEFAULT_VIEWER_OPACITY);
    }

    public void setViewerOpacity(final int opacity) {
        mPreferences.edit().putInt(KEY_VIEWER_OPACITY, opacity).commit();
    }

    public int getViewerScale() {
        return mPreferences.getInt(KEY_VIEWER_SCALE, DEFAULT_VIEWER_SCALE);
    }

    public void setViewerScale(final int scale) {
        mPreferences.edit().putInt(KEY_VIEWER_SCALE, scale).commit();
    }

    @NonNull
    public PointF getViewerLocation() {
        return Optional.ofNullable(mPreferences.getString(KEY_VIEWER_LOCATION, null))
            .map(value -> mGson.fromJson(value, PointF.class))
            .orElseGet(() -> new PointF(DEFAULT_VIEWER_LOCATION.x, DEFAULT_VIEWER_LOCATION.y));
    }

    public void setViewerLocation(@NonNull final PointF location) {
        mPreferences.edit().putString(KEY_VIEWER_LOCATION, mGson.toJson(location)).commit();
    }

    public boolean isViewerRounded() {
        return mPreferences.getBoolean(KEY_VIEWER_ROUNDED, true);
    }

    public void setViewerRounded(final boolean isRounded) {
        mPreferences.edit().putBoolean(KEY_VIEWER_ROUNDED, isRounded).commit();
    }

    public boolean isDiscoveryEnabled() {
        return mPreferences.getBoolean(KEY_DISCOVERY_ENABLED, true);
    }

    public void setDiscoveryEnabled(final boolean isEnabled) {
        mPreferences.edit().putBoolean(KEY_DISCOVERY_ENABLED, isEnabled).commit();
    }

    @FunctionalInterface
    public interface Observer {
        void onChanged(@Nullable String key);
    }

    private static class InstanceHolder {
        private static final Preferences INSTANCE = new Preferences();
    }
}
