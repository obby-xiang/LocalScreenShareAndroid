package com.obby.android.localscreenshare.authorization.store;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Raw persistence of the viewing authorizations.
 *
 * <p>One string document, read once at construction and replaced on every change. The same shape
 * as the trust pin storage, kept separate so a corrupted or reset document never takes the trust
 * pins with it.</p>
 */
public interface AuthorizationStorage {

    /**
     * Returns the stored document, or null when nothing was ever written.
     */
    @Nullable
    String read();

    /**
     * Replaces the stored document.
     *
     * @throws RuntimeException when the write failed; the store keeps its in-memory state
     */
    void write(@NonNull String value);
}
