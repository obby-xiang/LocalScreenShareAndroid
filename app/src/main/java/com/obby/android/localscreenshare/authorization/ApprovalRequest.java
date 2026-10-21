package com.obby.android.localscreenshare.authorization;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * One peer's request to watch, waiting for the server user's answer.
 *
 * <p>The request carries the identity proven by the handshake, not what the peer claims: the id and
 * the fingerprint come from the peer certificate, and the optional name is only a display label.
 * A peer without a verified identity (the hidden plaintext switch) has null identity fields; it can
 * be allowed once but never remembered, because there is nothing durable to remember.</p>
 *
 * <p>Immutable value object, pure JVM.</p>
 */
public final class ApprovalRequest {

    private final long mRequestId;

    @Nullable
    private final String mDeviceId;

    @Nullable
    private final String mFingerprint;

    @NonNull
    private final String mDeviceName;

    private final long mRequestedAtMs;

    /**
     * Creates a request.
     *
     * @param requestId     process-wide request id chosen by the gate
     * @param deviceId      verified device id, or null when the call has none
     * @param fingerprint   verified fingerprint, or null when the call has none
     * @param deviceName    declared display name, or null when the peer declared none
     * @param requestedAtMs monotonic time the request was created
     */
    public ApprovalRequest(final long requestId, @Nullable final String deviceId,
                           @Nullable final String fingerprint, @Nullable final String deviceName,
                           final long requestedAtMs) {
        mRequestId = requestId;
        mDeviceId = normalize(deviceId);
        mFingerprint = normalize(fingerprint);
        mDeviceName = normalize(deviceName) == null ? "" : normalize(deviceName);
        mRequestedAtMs = requestedAtMs;
    }

    /**
     * Returns the process-wide request id.
     */
    public long getRequestId() {
        return mRequestId;
    }

    /**
     * Returns the verified device id, or null when the call carries no identity.
     */
    @Nullable
    public String getDeviceId() {
        return mDeviceId;
    }

    /**
     * Returns the verified fingerprint, or null when the call carries no identity.
     */
    @Nullable
    public String getFingerprint() {
        return mFingerprint;
    }

    /**
     * Returns the declared display name, or an empty string when the peer declared none.
     */
    @NonNull
    public String getDeviceName() {
        return mDeviceName;
    }

    /**
     * Returns when the request was created, on the gate's clock.
     */
    public long getRequestedAtMs() {
        return mRequestedAtMs;
    }

    /**
     * Returns whether this request carries an identity that can be remembered.
     */
    public boolean hasVerifiedIdentity() {
        return mDeviceId != null && mFingerprint != null;
    }

    @Override
    public boolean equals(@Nullable final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ApprovalRequest)) {
            return false;
        }
        final ApprovalRequest request = (ApprovalRequest) other;
        return mRequestId == request.mRequestId
            && mRequestedAtMs == request.mRequestedAtMs
            && Objects.equals(mDeviceId, request.mDeviceId)
            && Objects.equals(mFingerprint, request.mFingerprint)
            && mDeviceName.equals(request.mDeviceName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mRequestId, mDeviceId, mFingerprint, mDeviceName, mRequestedAtMs);
    }

    @NonNull
    @Override
    public String toString() {
        return "ApprovalRequest{requestId=" + mRequestId + ", deviceId=" + mDeviceId + "}";
    }

    @Nullable
    private static String normalize(@Nullable final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
