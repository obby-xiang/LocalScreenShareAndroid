package com.obby.android.localscreenshare.server;

import androidx.annotation.NonNull;

import java.util.Comparator;
import java.util.List;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;

@Accessors(prefix = "m")
@Data
@Builder(builderClassName = "Builder", toBuilder = true)
public class LssServerStats {
    private final long mStartTimestamp;

    private final long mEndTimestamp;

    private final long mOutboundDataSize;

    private final long mOutboundDataRate;

    @NonNull
    private final List<TransportStats> mTransports;

    @Accessors(prefix = "m")
    @Data
    @lombok.Builder(builderClassName = "Builder", toBuilder = true)
    public static class TransportStats implements Comparable<TransportStats> {
        private static final Comparator<TransportStats> COMPARATOR =
            Comparator.nullsLast(Comparator.comparing(TransportStats::getRemoteAddress)
                .thenComparing(TransportStats::getOutboundDataRate, Comparator.reverseOrder()));

        @NonNull
        private final String mRemoteAddress;

        private final long mOutboundDataSize;

        private final long mOutboundDataRate;

        @Override
        public int compareTo(TransportStats transportStats) {
            return COMPARATOR.compare(this, transportStats);
        }
    }
}
