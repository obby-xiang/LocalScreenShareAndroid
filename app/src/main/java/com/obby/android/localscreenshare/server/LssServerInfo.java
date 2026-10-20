package com.obby.android.localscreenshare.server;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Immutable description of the running server, shared with the UI and used to build the NSD
 * advertisement.
 *
 * <p>{@link Parcelable} because the service sends it to activities through a {@code Messenger}.
 * The host address is a placeholder until the server resolves its own NSD registration. The
 * discovery identity is {@link #getDeviceId()}, whose first eight characters form the NSD instance
 * name; the legacy {@link #getId()} carries the same value until the settings batch removes it.</p>
 */
@Accessors(prefix = "m")
@Data
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(builderClassName = "Builder", toBuilder = true)
public class LssServerInfo implements Parcelable {
    public static final Creator<LssServerInfo> CREATOR = new Creator<>() {
        @Override
        public LssServerInfo createFromParcel(Parcel in) {
            return new LssServerInfo(in);
        }

        @Override
        public LssServerInfo[] newArray(int size) {
            return new LssServerInfo[size];
        }
    };

    /**
     * Stable service identity; the same value as {@link #getDeviceId()}.
     *
     * @deprecated the discovery identity is {@link #getDeviceId()}; this accessor only exists so
     *     the media path and the legacy UI contract keep compiling until the settings batch
     *     migrates them.
     */
    @Deprecated
    @NonNull
    private final String mId;

    /**
     * User-visible server name advertised over NSD.
     */
    @NonNull
    private final String mName;

    /**
     * Host address clients can reach; a placeholder until NSD resolution succeeds.
     */
    @NonNull
    private final String mHostAddress;

    /**
     * Bound gRPC port.
     */
    private final int mPort;

    /**
     * Discovery identity of this device, the 26-character Crockford base32 id derived from the
     * AndroidKeyStore key. Peers use it as the device primary key and as the NSD instance name.
     */
    @NonNull
    private final String mDeviceId;

    /**
     * Fingerprint of the identity public key, {@code base64url(SHA-256(SPKI))}.
     *
     * <p>It is a claim until transport authentication exists (the media stream is not encrypted
     * yet), so it is never broadcast over NSD and only travels over the application-level
     * handshake.</p>
     */
    @NonNull
    private final String mFingerprint;

    @SuppressWarnings("DataFlowIssue")
    private LssServerInfo(@NonNull final Parcel in) {
        mId = in.readString();
        mName = in.readString();
        mHostAddress = in.readString();
        mPort = in.readInt();
        // Appended fields keep the original read order intact.
        mDeviceId = in.readString();
        mFingerprint = in.readString();
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
        dest.writeString(mDeviceId);
        dest.writeString(mFingerprint);
    }
}
