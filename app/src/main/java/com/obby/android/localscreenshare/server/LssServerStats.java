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

/**
 * Immutable snapshot of the server's outbound traffic for one collection interval.
 *
 * <p>{@link Parcelable} because the service forwards it to activities. The aggregate values cover
 * the interval since the previous collection; {@link #getTransports()} contains one entry per
 * transport present when the snapshot was collected.</p>
 */
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

    /**
     * Total outbound wire bytes over the interval.
     */
    private final long mOutboundDataSize;

    /**
     * Total outbound wire bytes per second over the interval.
     */
    private final long mOutboundDataRate;

    /**
     * Per-remote-address breakdown for the same interval.
     */
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

    /**
     * Outbound traffic of one remote transport.
     *
     * <p>Ordered by remote address, then by descending data rate, so equal addresses list their
     * busiest transport first.</p>
     */
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

        /**
         * Remote address as reported by the gRPC transport, in socket address form.
         */
        @NonNull
        private final String mRemoteAddress;

        /**
         * Outbound wire bytes over the interval.
         */
        private final long mOutboundDataSize;

        /**
         * Outbound wire bytes per second over the interval.
         */
        private final long mOutboundDataRate;

        @SuppressWarnings("DataFlowIssue")
        private TransportStats(@NonNull final Parcel in) {
            mRemoteAddress = in.readString();
            mOutboundDataSize = in.readLong();
            mOutboundDataRate = in.readLong();
        }

        /**
         * Orders by remote address, then by descending outbound data rate.
         */
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
