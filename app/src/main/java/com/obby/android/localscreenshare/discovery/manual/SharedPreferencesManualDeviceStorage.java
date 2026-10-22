package com.obby.android.localscreenshare.discovery.manual;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * {@link ManualDeviceStorage} backed by a private {@code SharedPreferences} file.
 *
 * <p>The file is separate from the settings and from the favorites so that clearing either cannot
 * lose the addresses the user typed. Writes use {@code commit()} for the same reason the favorites
 * do: the value is on disk before the call returns, and the document is small and only written when
 * an entry actually changes.</p>
 */
@SuppressLint("ApplySharedPref")
public final class SharedPreferencesManualDeviceStorage implements ManualDeviceStorage {

    /**
     * Preference file holding the manual entries; independent of settings and favorites.
     */
    public static final String PREF_FILE_NAME = "lss-manual";

    /**
     * Key of the current manual entry document.
     */
    public static final String KEY_MANUAL_DEVICES = "manual_devices";

    @NonNull
    private final SharedPreferences mPreferences;

    /**
     * Creates the storage from an application context.
     */
    public SharedPreferencesManualDeviceStorage(@NonNull final Context context) {
        mPreferences = Objects.requireNonNull(context, "context")
            .getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE);
    }

    @Nullable
    @Override
    public String read() {
        return mPreferences.getString(KEY_MANUAL_DEVICES, null);
    }

    @Override
    public void write(@NonNull final String value) {
        mPreferences.edit().putString(KEY_MANUAL_DEVICES, value).commit();
    }
}
