package com.obby.android.localscreenshare.authorization.store;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * {@link AuthorizationStorage} backed by a private {@code SharedPreferences} file.
 *
 * <p>The file is deliberately separate from the settings, the favorites and the trust pins: a
 * settings reset must not silently grant access, and revoking an authorization must not touch the
 * identity pin of the same device. Writes use {@code commit()} so an "always allow" decision is on
 * disk before the connection it authorized proceeds.</p>
 */
@SuppressLint("ApplySharedPref")
public final class SharedPreferencesAuthorizationStorage implements AuthorizationStorage {

    /**
     * Preference file holding the viewing authorizations; independent of the other files.
     */
    public static final String PREF_FILE_NAME = "lss-authorization";

    /**
     * Key of the current authorization document.
     */
    public static final String KEY_AUTHORIZATIONS = "authorizations";

    @NonNull
    private final SharedPreferences mPreferences;

    /**
     * Creates the storage from an application context.
     */
    public SharedPreferencesAuthorizationStorage(@NonNull final Context context) {
        mPreferences = Objects.requireNonNull(context, "context")
            .getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE);
    }

    @Nullable
    @Override
    public String read() {
        return mPreferences.getString(KEY_AUTHORIZATIONS, null);
    }

    @Override
    public void write(@NonNull final String value) {
        mPreferences.edit().putString(KEY_AUTHORIZATIONS, value).commit();
    }
}
