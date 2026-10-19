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
 * The host address is a placeholder until the server resolves its own NSD registration; the id is
 * the service identity and is also used as the NSD service name.</p>
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
     * Host address clients can reach; a placeholder until NSD resolution succeeds.
     */
    @NonNull
    private final String mHostAddress;

    /**
     * Bound gRPC port.
     */
    private final int mPort;

    @SuppressWarnings("DataFlowIssue")
    private LssServerInfo(@NonNull final Parcel in) {
        mId = in.readString();
        mName = in.readString();
        mHostAddress = in.readString();
        mPort = in.readInt();
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
    }
}
