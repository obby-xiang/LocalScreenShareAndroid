package com.obby.android.localscreenshare.trust;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * One trust-on-first-use pin: the SPKI fingerprint the user accepted for a device id.
 *
 * <p>The pin is the only durable trust anchor for a peer. It is written once, after the user
 * confirmed the full fingerprint, and compared on every later handshake. A fingerprint from a
 * discovery announcement is never stored here; only a value recomputed from the peer certificate
 * can become a pin.</p>
 *
 * <p>Immutable value object, pure JVM. Persistence is owned by {@link TlsPinStore}.</p>
 */
public final class TlsPin {

    /**
     * Maximum length accepted for an id, matching the protocol's fingerprint field bound.
     */
    public static final int MAX_ID_LENGTH = 64;

    /**
     * Maximum length accepted for a fingerprint; the canonical form is 43 characters.
     */
    public static final int MAX_FINGERPRINT_LENGTH = 64;

    @NonNull
    private final String mId;

    @NonNull
    private final String mFingerprint;

    private final long mPinnedAtMs;

    /**
     * Creates a pin.
     *
     * @param id          device id the pin belongs to
     * @param fingerprint unpadded base64url SHA-256 of the peer SPKI
     * @param pinnedAtMs  wall-clock time the pin was written; informational only
     * @throws IllegalArgumentException if the id or the fingerprint is blank or too long
     */
    public TlsPin(@NonNull final String id, @NonNull final String fingerprint,
                  final long pinnedAtMs) {
        final String trimmedId = id == null ? "" : id.trim();
        final String trimmedFingerprint = fingerprint == null ? "" : fingerprint.trim();
        if (trimmedId.isEmpty() || trimmedId.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException("id must be 1.." + MAX_ID_LENGTH + " characters");
        }
        if (trimmedFingerprint.isEmpty()
            || trimmedFingerprint.length() > MAX_FINGERPRINT_LENGTH) {
            throw new IllegalArgumentException(
                "fingerprint must be 1.." + MAX_FINGERPRINT_LENGTH + " characters");
        }
        mId = trimmedId;
        mFingerprint = trimmedFingerprint;
        mPinnedAtMs = pinnedAtMs;
    }

    /**
     * Returns the device id this pin belongs to.
     */
    @NonNull
    public String getId() {
        return mId;
    }

    /**
     * Returns the pinned SPKI fingerprint.
     */
    @NonNull
    public String getFingerprint() {
        return mFingerprint;
    }

    /**
     * Returns the wall-clock time the pin was written; never used for a trust decision.
     */
    public long getPinnedAtMs() {
        return mPinnedAtMs;
    }

    /**
     * Returns a pin with the same id and a new fingerprint.
     */
    @NonNull
    public TlsPin withFingerprint(@NonNull final String fingerprint, final long pinnedAtMs) {
        return new TlsPin(mId, fingerprint, pinnedAtMs);
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TlsPin)) {
            return false;
        }
        final TlsPin pin = (TlsPin) other;
        return mPinnedAtMs == pin.mPinnedAtMs && mId.equals(pin.mId)
            && mFingerprint.equals(pin.mFingerprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mFingerprint, mPinnedAtMs);
    }

    @NonNull
    @Override
    public String toString() {
        return "TlsPin{id=" + mId + ", fingerprint=" + mFingerprint + "}";
    }
}
