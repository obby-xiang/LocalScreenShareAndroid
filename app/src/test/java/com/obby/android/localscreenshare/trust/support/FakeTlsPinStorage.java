package com.obby.android.localscreenshare.trust.support;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.trust.TlsPinStorage;

/**
 * In-memory {@link TlsPinStorage} for JVM tests.
 *
 * <p>Reproduces the two states the real storage can be in — a readable document and a write that
 * fails — without touching Android or the disk.</p>
 */
public final class FakeTlsPinStorage implements TlsPinStorage {

    @Nullable
    private String mValue;

    private boolean mFailWrites;

    private int mWriteCount;

    /**
     * Returns the stored document, or null when nothing was written.
     */
    @Nullable
    public String value() {
        return mValue;
    }

    /**
     * Replaces the stored document without counting a write, as a restore would.
     */
    public void setValue(@Nullable final String value) {
        mValue = value;
    }

    /**
     * Makes every write fail the way an unavailable preference file would.
     */
    public void failWrites() {
        mFailWrites = true;
    }

    /**
     * Returns how many writes were attempted.
     */
    public int writeCount() {
        return mWriteCount;
    }

    @Nullable
    @Override
    public String read() {
        return mValue;
    }

    @Override
    public void write(@NonNull final String value) {
        mWriteCount++;
        if (mFailWrites) {
            throw new IllegalStateException("storage unavailable");
        }
        mValue = value;
    }
}
