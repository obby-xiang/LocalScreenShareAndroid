package com.obby.android.localscreenshare.client;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Immutable snapshot of the inbound traffic measured by an {@link ScreenShareClient}.
 *
 * <p>Values cover the interval since the previous collection, not the whole session.</p>
 */
@Accessors(prefix = "m")
@Data
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(builderClassName = "Builder", toBuilder = true)
public class ClientStats {
    /**
     * Inbound wire bytes received during the collection interval.
     */
    private final long mInboundDataSize;

    /**
     * Inbound wire bytes per second over the collection interval.
     */
    private final long mInboundDataRate;
}
