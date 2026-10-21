package com.obby.android.localscreenshare.trust;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * {@link TlsPinStorage} backed by a private {@code SharedPreferences} file.
 *
 * <p>The file is separate from the settings and from the favorites: a trust decision must survive
 * a settings reset and must not be rewritten by a favorite rename. Writes use {@code commit()} so
 * a pin is on disk before the connection that accepted it proceeds.</p>
 */
@SuppressLint("ApplySharedPref")
public final class SharedPreferencesTlsPinStorage implements TlsPinStorage {

    /**
     * Preference file holding peer pins; independent of the settings file.
     */
    public static final String PREF_FILE_NAME = "lss-trust";

    /**
     * Key of the current pin document.
     */
    public static final String KEY_PINS = "pins";

    @NonNull
    private final SharedPreferences mPreferences;

    /**
     * Creates the storage from an application context.
     */
    public SharedPreferencesTlsPinStorage(@NonNull final Context context) {
        mPreferences = Objects.requireNonNull(context, "context")
            .getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE);
    }

    @Nullable
    @Override
    public String read() {
        return mPreferences.getString(KEY_PINS, null);
    }

    @Override
    public void write(@NonNull final String value) {
        mPreferences.edit().putString(KEY_PINS, value).commit();
    }
}
