package com.obby.android.localscreenshare.discovery.support;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.discovery.favorite.FavoriteStorage;

/**
 * In-memory {@link FavoriteStorage} that records the document between writes.
 *
 * <p>A test can construct a second store over the same fake to prove the JSON round trip, and can
 * seed or inspect the legacy document without touching Android preferences.</p>
 */
public final class FakeFavoriteStorage implements FavoriteStorage {

    @Nullable
    private String mDocument;

    @Nullable
    private String mLegacyDocument;

    private int mWrites;

    @Override
    @Nullable
    public String read() {
        return mDocument;
    }

    @Override
    public void write(@NonNull final String value) {
        mDocument = value;
        mWrites++;
    }

    @Nullable
    @Override
    public String readLegacy() {
        return mLegacyDocument;
    }

    @Override
    public void clearLegacy() {
        mLegacyDocument = null;
    }

    /** Seeds the current document directly. */
    public void setDocument(@Nullable final String document) {
        mDocument = document;
    }

    /** Seeds the legacy document directly. */
    public void setLegacyDocument(@Nullable final String document) {
        mLegacyDocument = document;
    }

    /** Returns the current serialized document, or null. */
    @Nullable
    public String document() {
        return mDocument;
    }

    /** Returns how many times {@link #write(String)} was called. */
    public int writes() {
        return mWrites;
    }
}
