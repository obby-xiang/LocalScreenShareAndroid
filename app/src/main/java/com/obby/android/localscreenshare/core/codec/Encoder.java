package com.obby.android.localscreenshare.core.codec;

import android.view.Surface;

import com.obby.android.localscreenshare.core.model.StreamParameters;

/**
 * Encoder for one stream instance, created by an {@link EncoderFactory}.
 *
 * <p><b>Lifecycle.</b> A factory returns an encoder that is not yet started. The caller starts it
 * once with {@link #start()}, feeds input (through the codec-specific submission method or through
 * the surface returned by {@link #getInputSurface()}), then calls {@link #stop()} to end output and
 * {@link #close()} to release resources. {@code stop()} is not a substitute for {@code close()}:
 * it leaves codec resources allocated so the encoder can still be closed cleanly, while
 * {@code close()} releases them. Both calls are idempotent.</p>
 *
 * <p><b>Output.</b> Every encoder writes to the {@link EncoderOutput} supplied at creation time.
 * Access units are emitted only between a successful {@code start()} and {@code close()}. A
 * surface-input encoder may emit from its own drain thread, whereas a pull-style encoder may emit
 * synchronously from the caller's submission thread; callers must not assume a specific callback
 * thread and must not call {@code start()}, {@code stop()} or {@code close()} from inside an
 * output callback.</p>
 *
 * <p><b>Input surface.</b> For surface-input codecs the input surface is exposed via
 * {@link #getInputSurface()}; non-surface codecs (e.g. JPEG) return {@code null}. The surface is
 * owned by the encoder, is valid until {@link #close()}, and must not be released by the caller.</p>
 */
public interface Encoder {

    /**
     * Starts the encoder so it can accept input and emit output.
     *
     * <p>Must be called once after creation and before the first input is submitted. Calling it
     * again or after {@link #close()} is a lifecycle violation; surface-input implementations
     * reject it with {@link IllegalStateException}.</p>
     */
    void start();

    /**
     * Stops producing output but keeps codec resources allocated.
     *
     * <p>Idempotent and a no-op for an encoder that was never started. After a normal return no
     * further {@link EncoderOutput} callback is delivered. Resources are released only by
     * {@link #close()}.</p>
     */
    void stop();

    /**
     * Returns the surface the producer must render into, or {@code null} when the codec does not
     * consume surface input.
     *
     * <p>For codecs whose {@link CodecDescriptor#isSurfaceInputRequired()} is true the value is
     * non-null between creation and {@link #close()}; it becomes {@code null} after close. A
     * {@code null} return from another codec is expected, not a failure.</p>
     */
    Surface getInputSurface();

    /**
     * Applies new rate and cadence hints without changing the stream identity, its descriptor or
     * its configuration epoch.
     *
     * <p>An encoder may apply only the values it supports live (typically the target bitrate) and
     * record the rest for the next encoder instance. Implementations may propagate a runtime
     * failure from the underlying codec.</p>
     */
    void updateParameters(StreamParameters parameters);

    /**
     * Asks the encoder to produce a random-access point as soon as possible.
     *
     * <p>Best effort: the runtime may coalesce or rate-limit requests, and the call is a no-op for
     * codecs whose frames are independently decodable. The request never emits an access unit
     * synchronously; the resulting join point is delivered through
     * {@link EncoderOutput#onAccessUnit}.</p>
     */
    void requestSyncPoint();

    /**
     * Releases all resources held by the encoder.
     *
     * <p>Idempotent; repeated calls are no-ops. Once close returns, no further callback is
     * delivered. A surface-input encoder stops its worker thread and waits for it before releasing
     * the codec; if the worker cannot be stopped safely the release is deferred rather than racing
     * the live thread, and a later close retries it.</p>
     */
    void close();
}
