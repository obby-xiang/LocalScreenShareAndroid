package com.obby.android.localscreenshare.support;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.Objects;
import java.util.Random;

/**
 * Bounded exponential retry schedule with optional jitter.
 *
 * <p>The policy owns only a counter: each {@link #nextDelayMs()} call advances one step along the
 * configured delay sequence and never goes past the last entry, so a permanently failing operation
 * keeps retrying at the slow pace instead of giving up. {@link #reset()} returns to the first step
 * and is meant to be called whenever the underlying condition changed, for example a network
 * switch. Jitter spreads retries of independent devices so they do not synchronize.</p>
 *
 * <p>The policy is not thread-safe; each discovery or registration session owns one instance and
 * drives it from the main thread.</p>
 */
public final class BackoffPolicy {

    /**
     * Default schedule used for NSD discovery and registration retries.
     */
    public static final long[] DEFAULT_DELAYS_MS = {1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L};

    /**
     * Default jitter as a fraction of the base delay, applied both earlier and later.
     */
    public static final double DEFAULT_JITTER_RATIO = 0.1d;

    private static final long MIN_DELAY_MS = 1L;

    /**
     * Maps a base delay to the delay actually used.
     */
    @FunctionalInterface
    public interface Jitter {
        /**
         * Returns the delay to use for {@code delayMs}; implementations must return a positive value.
         */
        long apply(long delayMs);
    }

    /**
     * Jitter that returns the base delay unchanged; used by tests for exact sequences.
     */
    public static final Jitter NO_JITTER = delayMs -> delayMs;

    @NonNull
    private final long[] mDelaysMs;

    @NonNull
    private final Jitter mJitter;

    private int mAttempts;

    private BackoffPolicy(@NonNull final long[] delaysMs, @NonNull final Jitter jitter) {
        mDelaysMs = Arrays.copyOf(delaysMs, delaysMs.length);
        mJitter = jitter;
    }

    /**
     * Returns a policy with the default schedule and 10% random jitter.
     */
    @NonNull
    public static BackoffPolicy defaults() {
        return withDelays(DEFAULT_DELAYS_MS, randomJitter(DEFAULT_JITTER_RATIO, new Random()));
    }

    /**
     * Returns a policy with the default schedule and no jitter.
     */
    @NonNull
    public static BackoffPolicy defaultsWithoutJitter() {
        return withDelays(DEFAULT_DELAYS_MS, NO_JITTER);
    }

    /**
     * Returns a jitter-free policy with the given delays.
     */
    @NonNull
    public static BackoffPolicy withoutJitter(@NonNull final long... delaysMs) {
        return withDelays(delaysMs, NO_JITTER);
    }

    /**
     * Returns a policy with the given delays.
     *
     * @throws IllegalArgumentException if the schedule is empty or a delay is negative
     */
    @NonNull
    public static BackoffPolicy withDelays(@NonNull final long[] delaysMs, @NonNull final Jitter jitter) {
        if (delaysMs.length == 0) {
            throw new IllegalArgumentException("delays must not be empty");
        }
        for (final long delayMs : delaysMs) {
            if (delayMs < 0L) {
                throw new IllegalArgumentException("delay must be non-negative: " + delayMs);
            }
        }
        return new BackoffPolicy(delaysMs, Objects.requireNonNull(jitter, "jitter"));
    }

    /**
     * Returns a jitter that multiplies the base delay by a factor in
     * {@code [1 - ratio, 1 + ratio]}, drawn from {@code random}.
     */
    @NonNull
    public static Jitter randomJitter(final double ratio, @NonNull final Random random) {
        if (ratio < 0d || ratio >= 1d) {
            throw new IllegalArgumentException("ratio must be in [0, 1): " + ratio);
        }
        return delayMs -> {
            if (delayMs == 0L) {
                return 0L;
            }
            final double factor = 1d + (random.nextDouble() * 2d - 1d) * ratio;
            return Math.max(MIN_DELAY_MS, Math.round(delayMs * factor));
        };
    }

    /**
     * Returns the delay for the next retry and advances the schedule.
     */
    public long nextDelayMs() {
        final long base = mDelaysMs[Math.min(mAttempts, mDelaysMs.length - 1)];
        mAttempts++;
        return Math.max(MIN_DELAY_MS, mJitter.apply(base));
    }

    /**
     * Returns the number of delays handed out since construction or the last {@link #reset()}.
     */
    public int attempts() {
        return mAttempts;
    }

    /**
     * Returns to the first step of the schedule.
     */
    public void reset() {
        mAttempts = 0;
    }
}
