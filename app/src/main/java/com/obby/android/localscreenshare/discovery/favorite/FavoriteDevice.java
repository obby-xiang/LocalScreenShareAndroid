package com.obby.android.localscreenshare.discovery.favorite;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * One persisted favorite: identity, last known address and the user's label.
 *
 * <p>The identity part is {@code id} plus {@code fingerprint}. The fingerprint is the trust
 * anchor: when a device answers with a different one the favorite is flagged
 * {@link #isIdentityChanged() changed} and is never rebound silently. The address is only a hint
 * — it is the last address the device was seen at, not evidence that it is reachable now.</p>
 *
 * <p>{@code alias} is what the list shows. {@code customAlias} records whether the user named the
 * device: while it is false the alias follows the name the device advertises, and a rename on the
 * remote side updates it; once the user renames the favorite the remote name never overwrites it.
 * {@code lastSuccessMs} orders the probes, so the address that worked last is tried first.</p>
 *
 * <p>Immutable; every change returns a new instance.</p>
 */
@Getter
@ToString
@EqualsAndHashCode
@Accessors(prefix = "m")
public final class FavoriteDevice {

    @NonNull
    private final String mId;

    @Nullable
    private final String mFingerprint;

    @NonNull
    private final String mHost;

    private final int mPort;

    @Nullable
    private final String mAlias;

    private final boolean mCustomAlias;

    private final boolean mIdentityChanged;

    private final long mLastSuccessMs;

    FavoriteDevice(@NonNull final String id, @Nullable final String fingerprint,
        @NonNull final String host, final int port, @Nullable final String alias,
        final boolean customAlias, final boolean identityChanged, final long lastSuccessMs) {
        if (id.trim().isEmpty()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (host.trim().isEmpty()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 0 || port > 0xFFFF) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        mId = id;
        mFingerprint = fingerprint == null || fingerprint.trim().isEmpty() ? null : fingerprint;
        mHost = host;
        mPort = port;
        mAlias = alias == null || alias.trim().isEmpty() ? null : alias.trim();
        mCustomAlias = customAlias;
        mIdentityChanged = identityChanged;
        mLastSuccessMs = lastSuccessMs;
    }

    /**
     * Creates a favorite without a recorded success.
     *
     * @param id device short id
     * @param fingerprint trust anchor, or null for a legacy entry
     * @param host last known address literal
     * @param port last known gRPC port
     * @param alias display label, or null to fall back to the advertised name
     * @param customAlias whether {@code alias} was chosen by the user
     */
    @NonNull
    public static FavoriteDevice create(@NonNull final String id, @Nullable final String fingerprint,
        @NonNull final String host, final int port, @Nullable final String alias,
        final boolean customAlias) {
        return new FavoriteDevice(id, fingerprint, host, port, alias, customAlias, false, 0L);
    }

    /**
     * True when this favorite has an address that can be probed.
     */
    public boolean hasAddress() {
        return mPort > 0;
    }

    /**
     * Returns a copy with a new last known address.
     */
    @NonNull
    public FavoriteDevice withAddress(@NonNull final String host, final int port) {
        return new FavoriteDevice(mId, mFingerprint, host, port, mAlias, mCustomAlias,
            mIdentityChanged, mLastSuccessMs);
    }

    /**
     * Returns a copy with a new label and its custom flag.
     */
    @NonNull
    public FavoriteDevice withAlias(@Nullable final String alias, final boolean customAlias) {
        return new FavoriteDevice(mId, mFingerprint, mHost, mPort, alias, customAlias,
            mIdentityChanged, mLastSuccessMs);
    }

    /**
     * Returns a copy with the identity-changed flag set or cleared.
     */
    @NonNull
    public FavoriteDevice withIdentityChanged(final boolean identityChanged) {
        return new FavoriteDevice(mId, mFingerprint, mHost, mPort, mAlias, mCustomAlias,
            identityChanged, mLastSuccessMs);
    }

    /**
     * Returns a copy that records a successful connection at {@code nowMs}.
     */
    @NonNull
    public FavoriteDevice withSuccess(@NonNull final String host, final int port,
        final long nowMs) {
        return new FavoriteDevice(mId, mFingerprint, host, port, mAlias, mCustomAlias,
            mIdentityChanged, nowMs);
    }

    /**
     * Returns a copy bound to a new fingerprint; used when the user confirms a change.
     */
    @NonNull
    public FavoriteDevice withFingerprint(@Nullable final String fingerprint) {
        return new FavoriteDevice(mId, fingerprint, mHost, mPort, mAlias, mCustomAlias,
            false, mLastSuccessMs);
    }

    /**
     * Returns the display label, or the id when the favorite has no alias.
     */
    @NonNull
    public String displayName() {
        return mAlias != null ? mAlias : mId;
    }

    /**
     * Returns whether two favorites describe the same device identity.
     */
    public boolean sameIdentity(@NonNull final FavoriteDevice other) {
        return Objects.equals(mId, other.mId) && Objects.equals(mFingerprint, other.mFingerprint);
    }
}
