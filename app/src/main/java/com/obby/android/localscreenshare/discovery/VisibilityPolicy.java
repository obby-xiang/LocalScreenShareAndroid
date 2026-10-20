package com.obby.android.localscreenshare.discovery;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.discovery.model.JoinPolicy;

import java.util.Locale;

/**
 * Visibility of this device towards peers, and what that implies for announcements and Register.
 *
 * <p>The four presets cover the product cases without exposing arbitrary durations:</p>
 *
 * <ul>
 *   <li>{@link Visibility#HIDDEN} announces nothing and answers no Register, so peers never learn
 *       the device exists.</li>
 *   <li>{@link Visibility#FAVORITES} still announces, but with {@link JoinPolicy#CLOSED}: a peer
 *       may try to join, and only a caller whose identity is already a favorite is accepted.</li>
 *   <li>{@link Visibility#VISIBLE} announces and accepts every caller with an identity.</li>
 *   <li>{@link Visibility#VISIBLE_10_MIN} behaves like {@link Visibility#VISIBLE} until the
 *       window set when it was chosen elapses, then falls back to {@link Visibility#HIDDEN} on the
 *       next evaluation. No timer is owned here; the caller evaluates against its clock.</li>
 * </ul>
 *
 * <p>The class is immutable and free of Android types, so the expiry rule is deterministic on the
 * JVM. The stored expiry is absolute epoch milliseconds, matching the value persisted next to the
 * preset.</p>
 */
public final class VisibilityPolicy {

    /** Length of the temporary visibility window. */
    public static final long VISIBLE_DURATION_MS = 10L * 60_000L;

    /** Preset chosen by the user. */
    public enum Visibility {
        HIDDEN("hidden"),
        FAVORITES("favorites"),
        VISIBLE("visible"),
        VISIBLE_10_MIN("visible_10_min");

        @NonNull
        private final String mWire;

        Visibility(@NonNull final String wire) {
            mWire = wire;
        }

        /** Returns the persisted wire value. */
        @NonNull
        public String wire() {
            return mWire;
        }

        /**
         * Parses a persisted wire value, accepting the enum name as well.
         *
         * @return the preset, or {@link #VISIBLE} for an absent or unknown value
         */
        @NonNull
        public static Visibility fromWire(@NonNull final String wire) {
            final String normalized = wire.trim().toLowerCase(Locale.ROOT);
            for (final Visibility visibility : values()) {
                if (visibility.mWire.equals(normalized)
                    || visibility.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                    return visibility;
                }
            }
            return VISIBLE;
        }
    }

    @NonNull
    private final Visibility mVisibility;

    private final long mExpiresAtMs;

    private VisibilityPolicy(@NonNull final Visibility visibility, final long expiresAtMs) {
        mVisibility = visibility;
        mExpiresAtMs = expiresAtMs;
    }

    /**
     * Creates a policy for a preset chosen at {@code nowMs}.
     *
     * <p>Only {@link Visibility#VISIBLE_10_MIN} carries an expiry; every other preset stores
     * {@code 0} and never expires.</p>
     */
    @NonNull
    public static VisibilityPolicy forVisibility(@NonNull final Visibility visibility, final long nowMs) {
        final long expiresAtMs = visibility == Visibility.VISIBLE_10_MIN ? nowMs + VISIBLE_DURATION_MS : 0L;
        return new VisibilityPolicy(visibility, expiresAtMs);
    }

    /**
     * Restores a policy from its persisted values.
     *
     * <p>An expiry in the past is kept; {@link #effective(long)} is what turns it into
     * {@link Visibility#HIDDEN}.</p>
     */
    @NonNull
    public static VisibilityPolicy of(@NonNull final Visibility visibility, final long expiresAtMs) {
        final long normalizedExpiry = visibility == Visibility.VISIBLE_10_MIN
            ? Math.max(expiresAtMs, 0L) : 0L;
        return new VisibilityPolicy(visibility, normalizedExpiry);
    }

    /** Returns the preset the user chose, which may have expired. */
    @NonNull
    public Visibility getVisibility() {
        return mVisibility;
    }

    /** Returns the absolute expiry of {@link Visibility#VISIBLE_10_MIN}, or {@code 0} when none. */
    public long getExpiresAtMs() {
        return mExpiresAtMs;
    }

    /** Returns whether the temporary window is over at {@code nowMs}. */
    public boolean isExpired(final long nowMs) {
        return mVisibility == Visibility.VISIBLE_10_MIN && nowMs >= mExpiresAtMs;
    }

    /** Returns the preset that applies at {@code nowMs}, collapsing an expired window to hidden. */
    @NonNull
    public Visibility effective(final long nowMs) {
        return isExpired(nowMs) ? Visibility.HIDDEN : mVisibility;
    }

    /** Returns whether this device announces itself at {@code nowMs}. */
    public boolean isAnnouncing(final long nowMs) {
        return effective(nowMs) != Visibility.HIDDEN;
    }

    /** Returns the join policy advertised at {@code nowMs}. */
    @NonNull
    public JoinPolicy joinPolicy(final long nowMs) {
        switch (effective(nowMs)) {
            case VISIBLE:
            case VISIBLE_10_MIN:
                return JoinPolicy.OPEN;
            case FAVORITES:
            case HIDDEN:
            default:
                return JoinPolicy.CLOSED;
        }
    }

    /**
     * Returns whether a Register call from a device with this favorite state is accepted.
     *
     * <p>{@link Visibility#HIDDEN} accepts nobody; {@link Visibility#FAVORITES} accepts only a
     * caller the local favorite store already knows; the visible presets accept everyone.</p>
     */
    public boolean acceptsRegister(final long nowMs, final boolean isFavorite) {
        switch (effective(nowMs)) {
            case VISIBLE:
            case VISIBLE_10_MIN:
                return true;
            case FAVORITES:
                return isFavorite;
            case HIDDEN:
            default:
                return false;
        }
    }

    @Override
    public String toString() {
        return "VisibilityPolicy{visibility=" + mVisibility + ", expiresAtMs=" + mExpiresAtMs + '}';
    }
}
