package com.obby.android.localscreenshare.support;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.App;

import java.util.Optional;
import java.util.UUID;

public final class Preferences {
    private static final String PREF_FILE_NAME = "preferences";

    private static final String KEY_LSS_SERVICE_ID = "lss_service_id";

    @NonNull
    private final SharedPreferences mPreferences;

    private Preferences() {
        mPreferences = App.get().getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE);
    }

    @NonNull
    public static Preferences get() {
        return InstanceHolder.INSTANCE;
    }

    @SuppressLint("ApplySharedPref")
    @NonNull
    public String getLssServiceId() {
        return Optional.ofNullable(mPreferences.getString(KEY_LSS_SERVICE_ID, null))
            .orElseGet(() -> {
                final String serviceId = UUID.randomUUID().toString().replace("-", "");
                mPreferences.edit().putString(KEY_LSS_SERVICE_ID, serviceId).commit();
                return serviceId;
            });
    }

    private static class InstanceHolder {
        private static final Preferences INSTANCE = new Preferences();
    }
}
