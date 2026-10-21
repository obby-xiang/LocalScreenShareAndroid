package com.obby.android.localscreenshare.authorization.store.support;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.authorization.store.AuthorizationStorage;

/**
 * In-memory authorization storage for tests.
 *
 * <p>Records how many documents were written and can be told to fail the next write, so the "a
 * failed write never fails the caller" contract is tested instead of assumed.</p>
 */
public final class FakeAuthorizationStorage implements AuthorizationStorage {

    @Nullable
    private String mDocument;

    private int mWriteCount;

    private boolean mIsWriteFailing;

    /**
     * Creates an empty storage.
     */
    public FakeAuthorizationStorage() {
    }

    /**
     * Creates a storage that already holds {@code document}.
     */
    public FakeAuthorizationStorage(@Nullable final String document) {
        mDocument = document;
    }

    @Nullable
    @Override
    public String read() {
        return mDocument;
    }

    @Override
    public void write(@NonNull final String value) {
        if (mIsWriteFailing) {
            throw new IllegalStateException("write failed");
        }
        mDocument = value;
        mWriteCount++;
    }

    /**
     * Makes every later write throw until {@link #setWriteFailing(boolean)} clears it.
     */
    public void setWriteFailing(final boolean isFailing) {
        mIsWriteFailing = isFailing;
    }

    /**
     * Returns the current document, or null when nothing was written.
     */
    @Nullable
    public String getDocument() {
        return mDocument;
    }

    /**
     * Returns how many writes succeeded.
     */
    public int getWriteCount() {
        return mWriteCount;
    }
}
