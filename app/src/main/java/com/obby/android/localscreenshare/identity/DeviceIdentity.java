package com.obby.android.localscreenshare.identity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable identity of this installation: the short id peers key on and the fingerprint that
 * anchors it.
 *
 * <p>The values are derived from the public half of the AndroidKeyStore key pair, never stored as
 * the source of truth. A stored copy only exists to detect that the key changed — after a restore
 * from a backup, after an app-data clear, or after an explicit reset — in which case
 * {@link #isChanged()} is true and the previous identity must not be silently rebound.</p>
 *
 * <p>Pure JVM value object: no Android type, no I/O, no secret material. The private key never
 * leaves the keystore.</p>
 */
public final class DeviceIdentity {

    /** Characters of {@link #getId()} used for the {@code LSS-} instance name and the UI fallback. */
    public static final int SHORT_ID_LENGTH = 8;

    /** Prefix of the NSD instance name built from {@link #shortId()}. */
    public static final String NAME_PREFIX = "LSS-";

    @NonNull
    private final String mId;

    @NonNull
    private final String mFingerprint;

    private final boolean mChanged;

    private DeviceIdentity(@NonNull final String id, @NonNull final String fingerprint,
        final boolean changed) {
        if (id.length() != SpkiId.ID_LENGTH) {
            throw new IllegalArgumentException("id must be " + SpkiId.ID_LENGTH + " characters");
        }
        if (fingerprint.length() != SpkiId.FINGERPRINT_LENGTH) {
            throw new IllegalArgumentException(
                "fingerprint must be " + SpkiId.FINGERPRINT_LENGTH + " characters");
        }
        mId = id;
        mFingerprint = fingerprint;
        mChanged = changed;
    }

    /**
     * Derives the identity from a key pair.
     *
     * @param keyPair key pair whose public half is hashed
     * @param lastKnownFingerprint fingerprint recorded by an earlier run, or null on first run
     * @throws IllegalArgumentException if the public key has no X.509 encoding
     */
    @NonNull
    public static DeviceIdentity fromKeyPair(@NonNull final KeyPair keyPair,
        @Nullable final String lastKnownFingerprint) {
        if (keyPair == null) {
            throw new IllegalArgumentException("keyPair must not be null");
        }
        final PublicKey publicKey = keyPair.getPublic();
        if (publicKey == null) {
            throw new IllegalArgumentException("keyPair has no public key");
        }
        final byte[] spkiDer = publicKey.getEncoded();
        if (spkiDer == null) {
            throw new IllegalArgumentException("public key has no X.509 encoding");
        }
        return fromSpki(spkiDer, lastKnownFingerprint);
    }

    /**
     * Derives the identity from an SPKI DER encoding; see {@link SpkiId}.
     *
     * @param spkiDer X.509 {@code SubjectPublicKeyInfo} bytes
     * @param lastKnownFingerprint fingerprint recorded by an earlier run, or null on first run
     */
    @NonNull
    public static DeviceIdentity fromSpki(@NonNull final byte[] spkiDer,
        @Nullable final String lastKnownFingerprint) {
        final String fingerprint = SpkiId.fingerprint(spkiDer);
        final boolean changed = lastKnownFingerprint != null && !lastKnownFingerprint.trim().isEmpty()
            && !lastKnownFingerprint.equals(fingerprint);
        return new DeviceIdentity(SpkiId.deviceId(spkiDer), fingerprint, changed);
    }

    /** Returns the 26-character Crockford base32 device id. */
    @NonNull
    public String getId() {
        return mId;
    }

    /** Returns the 43-character unpadded base64url fingerprint of the public key. */
    @NonNull
    public String getFingerprint() {
        return mFingerprint;
    }

    /**
     * True when a fingerprint from an earlier run differs from the derived one, which means the
     * key was regenerated (restore, data clear or explicit reset). Favorites bound to the old
     * identity must be re-confirmed by the user, never overwritten silently.
     */
    public boolean isChanged() {
        return mChanged;
    }

    /** Returns the first eight id characters, the suffix of the NSD instance name. */
    @NonNull
    public String shortId() {
        return mId.substring(0, SHORT_ID_LENGTH).toUpperCase(Locale.ROOT);
    }

    /** Returns the NSD instance name built from the short id. */
    @NonNull
    public String instanceName() {
        return NAME_PREFIX + shortId();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DeviceIdentity)) {
            return false;
        }
        final DeviceIdentity that = (DeviceIdentity) o;
        return mChanged == that.mChanged && mId.equals(that.mId)
            && mFingerprint.equals(that.mFingerprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mFingerprint, mChanged);
    }

    @Override
    @NonNull
    public String toString() {
        return "DeviceIdentity{id=" + mId + ", fingerprint=" + mFingerprint
            + ", changed=" + mChanged + "}";
    }
}
