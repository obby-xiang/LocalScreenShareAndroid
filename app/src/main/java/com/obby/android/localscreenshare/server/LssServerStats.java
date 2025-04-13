package com.obby.android.localscreenshare.server;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.util.Comparator;
import java.util.List;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;

@Accessors(prefix = "m")
@Data
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(builderClassName = "Builder", toBuilder = true)
public class LssServerStats implements Parcelable {
    public static final Creator<LssServerStats> CREATOR = new Creator<>() {
        @Override
        public LssServerStats createFromParcel(Parcel in) {
            return new LssServerStats(in);
        }

        @Override
        public LssServerStats[] newArray(int size) {
            return new LssServerStats[size];
        }
    };

    private final long mOutboundDataSize;

    private final long mOutboundDataRate;

    @NonNull
    private final List<TransportStats> mTransports;

    @SuppressWarnings("DataFlowIssue")
    private LssServerStats(@NonNull final Parcel in) {
        mOutboundDataSize = in.readLong();
        mOutboundDataRate = in.readLong();
        mTransports = in.createTypedArrayList(TransportStats.CREATOR);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mOutboundDataSize);
        dest.writeLong(mOutboundDataRate);
        dest.writeTypedList(mTransports);
    }

    @Accessors(prefix = "m")
    @Data
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @lombok.Builder(builderClassName = "Builder", toBuilder = true)
    public static class TransportStats implements Comparable<TransportStats>, Parcelable {
        public static final Creator<TransportStats> CREATOR = new Creator<>() {
            @Override
            public TransportStats createFromParcel(Parcel in) {
                return new TransportStats(in);
            }

            @Override
            public TransportStats[] newArray(int size) {
                return new TransportStats[size];
            }
        };

        private static final Comparator<TransportStats> COMPARATOR =
            Comparator.nullsLast(Comparator.comparing(TransportStats::getRemoteAddress)
                .thenComparing(TransportStats::getOutboundDataRate, Comparator.reverseOrder()));

        @NonNull
        private final String mRemoteAddress;

        private final long mOutboundDataSize;

        private final long mOutboundDataRate;

        @SuppressWarnings("DataFlowIssue")
        private TransportStats(@NonNull final Parcel in) {
            mRemoteAddress = in.readString();
            mOutboundDataSize = in.readLong();
            mOutboundDataRate = in.readLong();
        }

        @Override
        public int compareTo(TransportStats transportStats) {
            return COMPARATOR.compare(this, transportStats);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeString(mRemoteAddress);
            dest.writeLong(mOutboundDataSize);
            dest.writeLong(mOutboundDataRate);
        }
    }
}
