package com.obby.android.localscreenshare.discovery.favorite;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Raw persistence of the favorite document.
 *
 * <p>The store owns the JSON schema; the storage only moves one string in and out. Splitting the
 * two keeps the store testable on the JVM with an in-memory fake, while production uses a private
 * {@code SharedPreferences} file — the same backing style as {@code Preferences}, but a separate
 * file so clearing settings never drops favorites.</p>
 */
public interface FavoriteStorage {

    /**
     * Returns the serialized favorite list, or null when nothing was stored yet.
     */
    @Nullable
    String read();

    /**
     * Replaces the serialized favorite list.
     */
    void write(@NonNull String value);

    /**
     * Returns the serialized pre-identity favorite list, or null when there is nothing to
     * migrate.
     */
    @Nullable
    String readLegacy();

    /**
     * Removes the legacy document after a migration attempt.
     */
    void clearLegacy();
}
