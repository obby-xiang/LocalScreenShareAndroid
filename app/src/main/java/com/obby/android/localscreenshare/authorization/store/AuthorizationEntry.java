package com.obby.android.localscreenshare.authorization.store;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * One durable viewing authorization: the device identity the user allowed to watch.
 *
 * <p>The identity is the pair of the device id and its SPKI fingerprint; both must match on every
 * later request. The fingerprint is kept next to the id on purpose: an id alone would turn a single
 * "always allow" into a permanent pass for anything that can claim that id after a key change.</p>
 *
 * <p>Immutable value object, pure JVM. Persistence is owned by {@link AuthorizationStore}.</p>
 */
public final class AuthorizationEntry {

    /**
     * Maximum length accepted for a device id.
     */
    public static final int MAX_ID_LENGTH = 64;

    /**
     * Maximum length accepted for a fingerprint; the canonical form is 43 characters.
     */
    public static final int MAX_FINGERPRINT_LENGTH = 64;

    /**
     * Maximum length accepted for a device name; the protocol bounds the name field the same way.
     */
    public static final int MAX_NAME_LENGTH = 64;

    @NonNull
    private final String mDeviceId;

    @NonNull
    private final String mFingerprint;

    @NonNull
    private final String mDeviceName;

    private final long mGrantedAtMs;

    /**
     * Creates an authorization entry.
     *
     * @param deviceId    device id the authorization belongs to
     * @param fingerprint unpadded base64url SHA-256 of the peer SPKI
     * @param deviceName  display name the peer declared, or null when it declared none
     * @param grantedAtMs wall-clock time the authorization was written; informational only
     * @throws IllegalArgumentException if the id or the fingerprint is blank or too long
     */
    public AuthorizationEntry(@NonNull final String deviceId, @NonNull final String fingerprint,
                              @Nullable final String deviceName, final long grantedAtMs) {
        final String trimmedId = deviceId == null ? "" : deviceId.trim();
        final String trimmedFingerprint = fingerprint == null ? "" : fingerprint.trim();
        if (trimmedId.isEmpty() || trimmedId.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException("deviceId must be 1.." + MAX_ID_LENGTH
                + " characters");
        }
        if (trimmedFingerprint.isEmpty()
            || trimmedFingerprint.length() > MAX_FINGERPRINT_LENGTH) {
            throw new IllegalArgumentException("fingerprint must be 1.." + MAX_FINGERPRINT_LENGTH
                + " characters");
        }
        mDeviceId = trimmedId;
        mFingerprint = trimmedFingerprint;
        mDeviceName = normalizeName(deviceName);
        mGrantedAtMs = grantedAtMs;
    }

    /**
     * Returns the device id of the authorized identity.
     */
    @NonNull
    public String getDeviceId() {
        return mDeviceId;
    }

    /**
     * Returns the fingerprint of the authorized identity.
     */
    @NonNull
    public String getFingerprint() {
        return mFingerprint;
    }

    /**
     * Returns the declared device name, or an empty string when the peer declared none.
     */
    @NonNull
    public String getDeviceName() {
        return mDeviceName;
    }

    /**
     * Returns when the authorization was written, in wall-clock milliseconds.
     */
    public long getGrantedAtMs() {
        return mGrantedAtMs;
    }

    @Override
    public boolean equals(@Nullable final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AuthorizationEntry)) {
            return false;
        }
        final AuthorizationEntry entry = (AuthorizationEntry) other;
        return mGrantedAtMs == entry.mGrantedAtMs
            && mDeviceId.equals(entry.mDeviceId)
            && mFingerprint.equals(entry.mFingerprint)
            && mDeviceName.equals(entry.mDeviceName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDeviceId, mFingerprint, mDeviceName, mGrantedAtMs);
    }

    @NonNull
    @Override
    public String toString() {
        return "AuthorizationEntry{deviceId=" + mDeviceId + ", grantedAtMs=" + mGrantedAtMs + "}";
    }

    @NonNull
    private static String normalizeName(@Nullable final String name) {
        if (name == null) {
            return "";
        }
        final String trimmed = name.trim();
        return trimmed.length() <= MAX_NAME_LENGTH ? trimmed : trimmed.substring(0, MAX_NAME_LENGTH);
    }
}
