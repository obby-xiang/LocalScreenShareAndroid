package com.obby.android.localscreenshare.trust;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Raw persistence of the pin document.
 *
 * <p>The store owns the JSON schema; storage only moves one string in and out. Production writes
 * to a private {@code SharedPreferences} file separate from the settings, so clearing settings
 * cannot silently drop trust decisions. Tests substitute an in-memory fake.</p>
 */
public interface TlsPinStorage {

    /**
     * Returns the serialized pin document, or null when nothing was stored yet.
     */
    @Nullable
    String read();

    /**
     * Replaces the serialized pin document.
     */
    void write(@NonNull String value);
}
