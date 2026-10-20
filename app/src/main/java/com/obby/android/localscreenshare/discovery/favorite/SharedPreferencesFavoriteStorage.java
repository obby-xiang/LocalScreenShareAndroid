package com.obby.android.localscreenshare.discovery.favorite;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * {@link FavoriteStorage} backed by a private {@code SharedPreferences} file.
 *
 * <p>The file is separate from the settings file so that clearing or migrating settings cannot
 * lose favorites. Writes use {@code commit()} for the same reason the settings do: the value is on
 * disk before the call returns, and favorites are small and rarely written.</p>
 */
@SuppressLint("ApplySharedPref")
public final class SharedPreferencesFavoriteStorage implements FavoriteStorage {

    /** Preference file holding favorites; independent of the settings file. */
    public static final String PREF_FILE_NAME = "lss-favorites";

    /** Key of the current favorite document. */
    public static final String KEY_FAVORITES = "favorites";

    /** Key of the pre-identity favorite document, read once by the migration hook. */
    public static final String KEY_LEGACY_FAVORITES = "legacy_favorites";

    @NonNull
    private final SharedPreferences mPreferences;

    /** Creates the storage from an application context. */
    public SharedPreferencesFavoriteStorage(@NonNull final Context context) {
        mPreferences = Objects.requireNonNull(context, "context")
            .getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE);
    }

    @Nullable
    @Override
    public String read() {
        return mPreferences.getString(KEY_FAVORITES, null);
    }

    @Override
    public void write(@NonNull final String value) {
        mPreferences.edit().putString(KEY_FAVORITES, value).commit();
    }

    @Nullable
    @Override
    public String readLegacy() {
        return mPreferences.getString(KEY_LEGACY_FAVORITES, null);
    }

    @Override
    public void clearLegacy() {
        mPreferences.edit().remove(KEY_LEGACY_FAVORITES).commit();
    }
}
