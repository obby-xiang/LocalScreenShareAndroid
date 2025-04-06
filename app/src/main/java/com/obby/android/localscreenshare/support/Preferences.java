package com.obby.android.localscreenshare.support;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PointF;
import android.provider.Settings;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.App;

import java.util.Optional;
import java.util.UUID;

@SuppressLint("ApplySharedPref")
public final class Preferences {
    public static final String KEY_LSS_SERVER_PORT = "lss_server_port";

    private static final String PREF_FILE_NAME = "lss-preferences";

    private static final String KEY_LSS_SERVICE_ID = "lss_service_id";

    private static final String KEY_LSS_SERVICE_NAME = "lss_service_name";

    private static final String KEY_LSS_SERVICE_CHIP_POSITION_X = "lss_service_chip_position_x";

    private static final String KEY_LSS_SERVICE_CHIP_POSITION_Y = "lss_service_chip_position_y";

    @NonNull
    private final SharedPreferences mPreferences;

    private Preferences() {
        mPreferences = App.get().getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE);
    }

    @NonNull
    public static Preferences get() {
        return InstanceHolder.INSTANCE;
    }

    @NonNull
    public PointF getServiceChipPosition() {
        final float positionX = mPreferences.getFloat(KEY_LSS_SERVICE_CHIP_POSITION_X, 0f);
        final float positionY = mPreferences.getFloat(KEY_LSS_SERVICE_CHIP_POSITION_Y, 0f);
        return new PointF(positionX, positionY);
    }

    public void setServiceChipPosition(@NonNull final PointF position) {
        mPreferences.edit()
            .putFloat(KEY_LSS_SERVICE_CHIP_POSITION_X, position.x)
            .putFloat(KEY_LSS_SERVICE_CHIP_POSITION_Y, position.y)
            .commit();
    }

    @NonNull
    public String getLssServiceId() {
        return Optional.ofNullable(mPreferences.getString(KEY_LSS_SERVICE_ID, null))
            .orElseGet(() -> {
                final String serviceId = UUID.randomUUID().toString().replace("-", "");
                mPreferences.edit().putString(KEY_LSS_SERVICE_ID, serviceId).commit();
                return serviceId;
            });
    }

    @NonNull
    public String getLssServiceName() {
        return Optional.ofNullable(mPreferences.getString(KEY_LSS_SERVICE_NAME, null))
            .orElseGet(() -> {
                final String serviceName =
                    Settings.Global.getString(App.get().getContentResolver(), Settings.Global.DEVICE_NAME);
                mPreferences.edit().putString(KEY_LSS_SERVICE_NAME, serviceName).commit();
                return serviceName;
            });
    }

    public int getLssServerPort() {
        return mPreferences.getInt(KEY_LSS_SERVER_PORT, Constants.DEFAULT_LSS_SERVER_PORT);
    }

    public void setLssServerPort(final int port) {
        mPreferences.edit().putInt(KEY_LSS_SERVER_PORT, port).commit();
    }

    private static class InstanceHolder {
        private static final Preferences INSTANCE = new Preferences();
    }
}
