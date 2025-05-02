package com.obby.android.localscreenshare.support;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PointF;
import android.provider.Settings;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.obby.android.localscreenshare.App;

import java.util.Optional;
import java.util.UUID;

@SuppressLint("ApplySharedPref")
public final class Preferences {
    private static final String PREF_FILE_NAME = "lss-preferences";

    private static final String KEY_SERVER_PORT = "server_port";

    private static final String KEY_SERVICE_ID = "service_id";

    private static final String KEY_SERVICE_NAME = "service_name";

    private static final String KEY_PROJECTION_SECURE = "projection_secure";

    private static final String KEY_PROJECTION_QUALITY = "projection_quality";

    private static final String KEY_PROJECTION_SCALE_PERCENTAGE = "projection_scale_percentage";

    private static final String KEY_SERVICE_CHIP_LOCATION = "service_chip_location";

    private static final String KEY_VIEWER_SCALE_PERCENTAGE = "viewer_scale_percentage";

    private static final String KEY_VIEWER_LOCATION = "viewer_location";

    private static final int DEFAULT_SERVER_PORT = 8080;

    private static final int DEFAULT_PROJECTION_SCALE_PERCENTAGE = 100;

    private static final int DEFAULT_PROJECTION_QUALITY = 85;

    private static final int DEFAULT_VIEWER_SCALE_PERCENTAGE = 80;

    @NonNull
    private final SharedPreferences mPreferences;

    @NonNull
    private final Gson mGson;

    private Preferences() {
        mPreferences = App.get().getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE);
        mGson = new Gson();
    }

    @NonNull
    public static Preferences get() {
        return InstanceHolder.INSTANCE;
    }

    public int getServerPort() {
        return mPreferences.getInt(KEY_SERVER_PORT, DEFAULT_SERVER_PORT);
    }

    public void setServerPort(final int port) {
        mPreferences.edit().putInt(KEY_SERVER_PORT, port).commit();
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

    public boolean isProjectionSecure() {
        return mPreferences.getBoolean(KEY_PROJECTION_SECURE, false);
    }

    public void setProjectionSecure(final boolean isSecure) {
        mPreferences.edit().putBoolean(KEY_PROJECTION_SECURE, isSecure).commit();
    }

    public int getProjectionQuality() {
        return mPreferences.getInt(KEY_PROJECTION_QUALITY, DEFAULT_PROJECTION_QUALITY);
    }

    public void setProjectionQuality(final int quality) {
        mPreferences.edit().putInt(KEY_PROJECTION_QUALITY, quality).commit();
    }

    public int getProjectionScalePercentage() {
        return mPreferences.getInt(KEY_PROJECTION_SCALE_PERCENTAGE, DEFAULT_PROJECTION_SCALE_PERCENTAGE);
    }

    public void setProjectionScalePercentage(final int percentage) {
        mPreferences.edit().putInt(KEY_PROJECTION_SCALE_PERCENTAGE, percentage).commit();
    }

    @NonNull
    public PointF getServiceChipLocation() {
        return Optional.ofNullable(mPreferences.getString(KEY_SERVICE_CHIP_LOCATION, null))
            .map(value -> mGson.fromJson(value, PointF.class))
            .orElseGet(PointF::new);
    }

    public void setServiceChipLocation(@NonNull final PointF location) {
        mPreferences.edit().putString(KEY_SERVICE_CHIP_LOCATION, mGson.toJson(location)).commit();
    }

    public int getViewerScalePercentage() {
        return mPreferences.getInt(KEY_VIEWER_SCALE_PERCENTAGE, DEFAULT_VIEWER_SCALE_PERCENTAGE);
    }

    public void setViewerScalePercentage(final int percentage) {
        mPreferences.edit().putInt(KEY_VIEWER_SCALE_PERCENTAGE, percentage).commit();
    }

    @NonNull
    public PointF getViewerLocation() {
        return Optional.ofNullable(mPreferences.getString(KEY_VIEWER_LOCATION, null))
            .map(value -> mGson.fromJson(value, PointF.class))
            .orElseGet(PointF::new);
    }

    public void setViewerLocation(@NonNull final PointF location) {
        mPreferences.edit().putString(KEY_VIEWER_LOCATION, mGson.toJson(location)).commit();
    }

    private static class InstanceHolder {
        private static final Preferences INSTANCE = new Preferences();
    }
}
