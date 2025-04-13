package com.obby.android.localscreenshare.discovery;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.util.Comparator;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;

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

    @NonNull
    private final String mId;

    @NonNull
    private final String mName;

    @NonNull
    private final String mHostAddress;

    private final int mPort;

    @SuppressWarnings("DataFlowIssue")
    private LssServiceInfo(@NonNull final Parcel in) {
        mId = in.readString();
        mName = in.readString();
        mHostAddress = in.readString();
        mPort = in.readInt();
    }

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
    }
}
