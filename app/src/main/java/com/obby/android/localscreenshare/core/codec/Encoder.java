package com.obby.android.localscreenshare.core.codec;

import android.view.Surface;

import com.obby.android.localscreenshare.core.model.StreamParameters;

/**
 * Lifecycle of a single encoder instance for one stream.
 *
 * <p>For surface-input codecs the input surface is exposed via
 * {@link #getInputSurface()}; non-surface codecs (e.g. JPEG) return {@code null}.</p>
 */
public interface Encoder {

    void start();

    void stop();

    Surface getInputSurface();

    void updateParameters(StreamParameters parameters);

    void requestSyncPoint();

    void close();
}
