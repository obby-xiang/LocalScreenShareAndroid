package com.obby.android.localscreenshare.core.codec;

import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;

/**
 * Sink for the output of an {@link Encoder}.
 *
 * <p>{@link #onAccessUnit} is the only mandatory callback and receives every displayable access
 * unit in encoding order. {@link #onEncoderConfig} and {@link #onEncoderFailure} are optional
 * notifications: their defaults ignore them, and an encoder calls them only when the codec needs a
 * deferred configuration phase or can fail asynchronously.</p>
 *
 * <p><b>Ordering.</b> For a codec whose descriptor carries configuration parts, the encoder calls
 * {@code onEncoderConfig} once before the first {@code onAccessUnit}. A configuration is never
 * followed by a different configuration within one encoder instance: changing the decode
 * configuration requires a new encoder and a new config epoch.</p>
 *
 * <p><b>Failure.</b> {@code onEncoderFailure} is terminal: at most one failure is reported per
 * encoder and no further callback follows. Callbacks run on a thread chosen by the encoder and are
 * made outside its internal lock; implementations should hand work off and return quickly, and must
 * not call encoder lifecycle methods from a callback.</p>
 */
@FunctionalInterface
public interface EncoderOutput {

    /**
     * Receives one displayable access unit.
     *
     * <p>Called only between a successful {@code start()} and {@code close()}. Implementations
     * must not throw; an unchecked exception may be treated by the encoder as a terminal output
     * failure.</p>
     */
    void onAccessUnit(EncodedAccessUnit accessUnit);

    /**
     * Receives the encoder's resolved configuration before the first access unit of a codec that
     * publishes its decode configuration after start.
     *
     * <p>The default implementation ignores the notification. Encoders that never defer
     * configuration never call it.</p>
     */
    default void onEncoderConfig(EncoderConfig config) {
    }

    /**
     * Reports a terminal encoder failure.
     *
     * <p>Called at most once; no access unit or configuration follows. The default implementation
     * ignores the notification.</p>
     */
    default void onEncoderFailure(EncoderException failure) {
    }
}
