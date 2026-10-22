package com.obby.android.localscreenshare.discovery.support;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.discovery.manual.ManualDeviceStorage;

/**
 * In-memory {@link ManualDeviceStorage} that records the document between writes.
 *
 * <p>A test can construct a second store over the same fake to prove the JSON round trip, can seed a
 * document to exercise the parser, and can make writes fail to prove that a storage error never
 * loses the in-memory list.</p>
 */
public final class FakeManualDeviceStorage implements ManualDeviceStorage {

    @Nullable
    private String mDocument;

    private int mWrites;

    private boolean mFailWrites;

    @Override
    @Nullable
    public String read() {
        return mDocument;
    }

    @Override
    public void write(@NonNull final String value) {
        if (mFailWrites) {
            throw new IllegalStateException("write failed");
        }
        mDocument = value;
        mWrites++;
    }

    /**
     * Seeds the current document directly.
     */
    public void setDocument(@Nullable final String document) {
        mDocument = document;
    }

    /**
     * Returns the current serialized document, or null.
     */
    @Nullable
    public String document() {
        return mDocument;
    }

    /**
     * Returns how many times {@link #write(String)} completed.
     */
    public int writes() {
        return mWrites;
    }

    /**
     * Makes every following write throw, as a full or unwritable preference file would.
     */
    public void setFailWrites(final boolean failWrites) {
        mFailWrites = failWrites;
    }
}
