package com.obby.android.localscreenshare.discovery.manual;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Raw persistence of the manual entry document.
 *
 * <p>The store owns the JSON schema; the storage only moves one string in and out. Splitting the
 * two keeps the store testable on the JVM with an in-memory fake, while production uses a private
 * {@code SharedPreferences} file — the same backing style as {@code FavoriteStorage}, but a separate
 * file so clearing favorites never drops the addresses the user typed by hand.</p>
 */
public interface ManualDeviceStorage {

    /**
     * Returns the serialized manual entry list, or null when nothing was stored yet.
     */
    @Nullable
    String read();

    /**
     * Replaces the serialized manual entry list.
     */
    void write(@NonNull String value);
}
