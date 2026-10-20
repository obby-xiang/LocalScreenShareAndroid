package com.obby.android.localscreenshare.discovery;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Comparator;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Immutable description of one discovered screen-stream server.
 *
 * <p>Carries the identity advertised over NSD (service id and display name) together with the
 * resolved host address and gRPC port. Instances are {@link Parcelable} so they can cross process
 * boundaries, and are ordered by host address, then port, name and id, which gives the discovery
 * list a stable order regardless of NSD callback order.</p>
 */
@Accessors(prefix = "m")
@Data
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(builderClassName = "Builder", toBuilder = true)
public class LssServiceInfo implements Comparable<LssServiceInfo>, Parcelable {
    public static final Creator<LssServiceInfo> CREATOR = new Creator<>() {
        @Override
        public LssServiceInfo createFromParcel(Parcel in) {
            return new LssServiceInfo(in);
        }

        @Override
        public LssServiceInfo[] newArray(int size) {
            return new LssServiceInfo[size];
        }
    };

    private static final Comparator<LssServiceInfo> COMPARATOR =
        Comparator.nullsLast(Comparator.comparing(LssServiceInfo::getHostAddress)
            .thenComparing(LssServiceInfo::getPort)
            .thenComparing(LssServiceInfo::getName)
            .thenComparing(LssServiceInfo::getId));

    /**
     * Stable service identity, generated per installation and also used as the NSD service name.
     */
    @NonNull
    private final String mId;

    /**
     * User-visible server name advertised over NSD.
     */
    @NonNull
    private final String mName;

    /**
     * Resolved host address literal used to open the gRPC channel.
     */
    @NonNull
    private final String mHostAddress;

    /**
     * Resolved gRPC port.
     */
    private final int mPort;

    /**
     * Fingerprint of the server identity public key, or null when the channel that reported the
     * server did not carry one.
     *
     * <p>NSD never advertises it; the application-level handshake does. It is the trust anchor
     * used to detect that a server id now belongs to a different key.</p>
     */
    @Nullable
    private final String mFingerprint;

    @SuppressWarnings("DataFlowIssue")
    private LssServiceInfo(@NonNull final Parcel in) {
        mId = in.readString();
        mName = in.readString();
        mHostAddress = in.readString();
        mPort = in.readInt();
        // Appended field keeps the original read order intact.
        mFingerprint = in.readString();
    }

    /**
     * Orders by host address, port, name and id.
     */
    @Override
    public int compareTo(LssServiceInfo serviceInfo) {
        return COMPARATOR.compare(this, serviceInfo);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeString(mName);
        dest.writeString(mHostAddress);
        dest.writeInt(mPort);
        dest.writeString(mFingerprint);
    }
}
