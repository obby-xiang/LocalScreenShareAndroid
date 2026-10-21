package com.obby.android.localscreenshare.server;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
public class ServerStats implements Parcelable {
    public static final Creator<ServerStats> CREATOR = new Creator<>() {
        @Override
        public ServerStats createFromParcel(Parcel in) {
            return new ServerStats(in);
        }

        @Override
        public ServerStats[] newArray(int size) {
            return new ServerStats[size];
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
    private ServerStats(@NonNull final Parcel in) {
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
     * Outbound traffic of one remote transport, optionally with the send session attached to it.
     *
     * <p>Ordered by remote address, then by client id, then by descending data rate, so equal
     * addresses list their busiest transport first and two sessions of the same address keep a
     * stable order.</p>
     *
     * <p><b>Client block.</b> {@code clientId} is null for a row that carries traffic without a
     * session, which is what an address keeps after its last viewer detached. When one address
     * carries more than one session the payload bytes stay on the first row of that address and the
     * other rows report zero bytes: the transport counter is per address, so splitting it between
     * sessions would invent a value.</p>
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
                .thenComparing(TransportStats::getClientId,
                    Comparator.nullsFirst(Comparator.naturalOrder()))
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

        /**
         * Client id of the session attached to this address, or null when no session maps here.
         */
        @Nullable
        private final String mClientId;

        /**
         * Session state name, or null when no session maps here.
         */
        @Nullable
        private final String mClientState;

        /**
         * Units still queued for this client at snapshot time.
         */
        private final long mQueuedUnits;

        /**
         * Payload bytes still queued for this client at snapshot time.
         */
        private final long mQueuedBytes;

        /**
         * Units this client's queue dropped over the session lifetime.
         */
        private final long mDroppedUnits;

        /**
         * Resynchronization episodes of this client's session.
         */
        private final long mResyncCount;

        /**
         * Units successfully sent to this client.
         */
        private final long mSentUnits;

        /**
         * 95th percentile of one transport send call in milliseconds, zero when not measured.
         */
        private final long mSendLatencyP95Ms;

        @SuppressWarnings("DataFlowIssue")
        private TransportStats(@NonNull final Parcel in) {
            mRemoteAddress = in.readString();
            mOutboundDataSize = in.readLong();
            mOutboundDataRate = in.readLong();
            mClientId = in.readString();
            mClientState = in.readString();
            mQueuedUnits = in.readLong();
            mQueuedBytes = in.readLong();
            mDroppedUnits = in.readLong();
            mResyncCount = in.readLong();
            mSentUnits = in.readLong();
            mSendLatencyP95Ms = in.readLong();
        }

        /**
         * Orders by remote address, then client id, then descending outbound data rate.
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
            dest.writeString(mClientId);
            dest.writeString(mClientState);
            dest.writeLong(mQueuedUnits);
            dest.writeLong(mQueuedBytes);
            dest.writeLong(mDroppedUnits);
            dest.writeLong(mResyncCount);
            dest.writeLong(mSentUnits);
            dest.writeLong(mSendLatencyP95Ms);
        }
    }
}
