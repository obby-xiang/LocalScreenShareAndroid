package com.obby.android.localscreenshare.discovery.manual;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * One device the user added by typing its address, as it is kept across process restarts.
 *
 * <p>The manual entry is the fallback that works when announcements are filtered and scanning is
 * blocked, so it must survive a restart: the address alone is enough to probe, and the identity
 * learned from the confirming handshake lets the restored row reuse the same store entry as the
 * device the network reports later.</p>
 *
 * <p>{@code id} is the device id the peer returned from the accepted handshake, never an address
 * derived value: {@code DeviceStore} keys its entries by that id, which is what keeps a restored
 * manual entry and an announced device with the same identity in one row instead of two. The
 * fingerprint is the trust anchor of that identity and the name is the name the peer advertised,
 * so a restored row shows the same label before the first re-probe answers.</p>
 *
 * <p>Immutable; every change returns a new instance.</p>
 */
@Getter
@ToString
@EqualsAndHashCode
@Accessors(prefix = "m")
public final class ManualDevice {

    @NonNull
    private final String mId;

    @Nullable
    private final String mFingerprint;

    @Nullable
    private final String mName;

    @NonNull
    private final String mHost;

    private final int mPort;

    ManualDevice(@NonNull final String id, @Nullable final String fingerprint,
        @Nullable final String name, @NonNull final String host, final int port) {
        if (id.trim().isEmpty()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (host.trim().isEmpty()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port <= 0 || port > 0xFFFF) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        mId = id;
        mFingerprint = fingerprint == null || fingerprint.trim().isEmpty() ? null : fingerprint;
        mName = name == null || name.trim().isEmpty() ? null : name.trim();
        mHost = host;
        mPort = port;
    }

    /**
     * Creates one manual entry.
     *
     * @param id          device id the peer returned from the accepted handshake
     * @param fingerprint trust anchor of that identity, or null when the peer advertised none
     * @param name        name the peer advertised, or null
     * @param host        address literal the user typed
     * @param port        gRPC port that answered; must be in {@code 1..65535}
     * @throws IllegalArgumentException if the id or host is blank or the port is out of range
     */
    @NonNull
    public static ManualDevice create(@NonNull final String id, @Nullable final String fingerprint,
        @Nullable final String name, @NonNull final String host, final int port) {
        return new ManualDevice(id, fingerprint, name, host, port);
    }

    /**
     * Returns whether this entry is stored at {@code host:port}.
     */
    public boolean isAt(@NonNull final String host, final int port) {
        return mPort == port && mHost.equals(host);
    }
}
