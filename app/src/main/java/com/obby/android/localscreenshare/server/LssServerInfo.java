package com.obby.android.localscreenshare.server;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;

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

    @NonNull
    private final String mId;

    @NonNull
    private final String mName;

    @NonNull
    private final String mHostAddress;

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
