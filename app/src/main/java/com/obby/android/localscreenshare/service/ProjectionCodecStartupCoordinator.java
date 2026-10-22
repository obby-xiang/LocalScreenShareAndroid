package com.obby.android.localscreenshare.service;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.core.codec.CodecNotFoundException;
import com.obby.android.localscreenshare.core.codec.EncoderException;

/**
 * Startup rule of the capture codec: try H.264 first and fall back to JPEG when it cannot start.
 *
 * <p>H.264 is the primary capture mode: it costs far less bandwidth than a JPEG per frame and is
 * what every viewer expects. A device can nevertheless refuse it at the moment the share starts -
 * no hardware encoder, the encoder is down after a driver restart, or the configured size is
 * rejected - and before this rule existed the only outcome was the generic "could not start the
 * share" path, which asked the user to try again without telling what to change.</p>
 *
 * <p><b>Which failures fall back.</b> Only failures of the codec itself: a
 * {@link CodecNotFoundException} for the requested id and an {@link EncoderException} from creating,
 * validating or starting the encoder. Anything else - a closed runtime, a rejected stream
 * registration, an out-of-memory error - is a defect of the caller or of the platform, and hiding it
 * behind a codec change would only make it harder to diagnose. {@link EncoderException} extends
 * {@link IllegalStateException}, so the check is by type and never by message.</p>
 *
 * <p><b>Scope of the fallback.</b> Exactly one retry, inside the same start: the second attempt runs
 * the JPEG pipeline and its own failure propagates unchanged. Nothing is switched while a session is
 * running, and the JPEG choice is not remembered - the rule only sees the codecs it is asked to
 * start, so the next share tries H.264 again. A device that needs JPEG permanently is served by the
 * explicit codec setting instead.</p>
 *
 * <p>The class holds no state and touches no Android type or preference, so the decision and the
 * retry order are covered by JVM tests with injected start attempts.</p>
 */
public final class ProjectionCodecStartupCoordinator {

    /**
     * Codec the capture pipeline ended up using.
     */
    public enum Codec {
        /**
         * H.264 started, which is the requested mode.
         */
        H264,

        /**
         * The pipeline runs the JPEG compatibility mode.
         */
        JPEG
    }

    /**
     * Starts the capture pipeline with one codec choice.
     */
    @FunctionalInterface
    public interface Starter {

        /**
         * Builds and starts the pipeline.
         *
         * @param h264 {@code true} for H.264, {@code false} for JPEG
         * @throws RuntimeException when the pipeline cannot start; a codec failure of the H.264
         *                          attempt is what triggers the fallback
         */
        void start(boolean h264);
    }

    /**
     * Receives the failure that triggered the fallback, for the log line and the user-visible note.
     */
    @FunctionalInterface
    public interface FallbackListener {

        /**
         * Called once, after the H.264 attempt failed and before the JPEG attempt runs.
         *
         * @param failure failure of the H.264 attempt; never null
         */
        void onFallback(@NonNull RuntimeException failure);
    }

    private ProjectionCodecStartupCoordinator() {
    }

    /**
     * Starts the pipeline with H.264 and retries once with JPEG when its encoder cannot start.
     *
     * @param h264Requested   whether the user asked for H.264; {@code false} starts JPEG directly
     * @param starter         attempts to start the pipeline; required
     * @param fallbackListener receiver of the triggering failure; required
     * @return the codec the pipeline runs; never null
     * @throws IllegalArgumentException if a callback is null
     * @throws RuntimeException         the failure of the H.264 attempt when it does not qualify for
     *                                  a fallback, or the failure of the JPEG attempt, unchanged
     */
    @NonNull
    public static Codec start(final boolean h264Requested, @NonNull final Starter starter,
        @NonNull final FallbackListener fallbackListener) {
        if (starter == null) {
            throw new IllegalArgumentException("starter must not be null");
        }
        if (fallbackListener == null) {
            throw new IllegalArgumentException("fallbackListener must not be null");
        }
        if (!h264Requested) {
            starter.start(false);
            return Codec.JPEG;
        }

        try {
            starter.start(true);
            return Codec.H264;
        } catch (RuntimeException failure) {
            if (!isCodecStartupFailure(failure)) {
                throw failure;
            }
            fallbackListener.onFallback(failure);
        }

        // The only second attempt; its failure is the start failure and propagates unchanged.
        starter.start(false);
        return Codec.JPEG;
    }

    /**
     * Returns whether {@code failure} is a codec startup failure that the fallback covers.
     */
    private static boolean isCodecStartupFailure(@Nullable final Throwable failure) {
        return failure instanceof CodecNotFoundException || failure instanceof EncoderException;
    }
}
