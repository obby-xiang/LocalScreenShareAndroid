package com.obby.android.localscreenshare.core.net;

import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;

/**
 * Receive-side contract of a media channel, symmetric to the send-side transport.
 *
 * <p>An implementation delivers what arrived on one channel to a receiver that already knows how
 * to apply descriptors and access units, so a channel that is not gRPC does not need its own
 * counters, its own arrival tracking or its own session driving: it only decodes the wire format
 * and reports the model objects.</p>
 *
 * <p><b>Threading.</b> All three callbacks are made on the single reading thread of the channel,
 * in wire order, and never concurrently with each other. The receiver they are forwarded to is not
 * thread safe, so a channel must not deliver from two threads and must not deliver after
 * {@link #onChannelFailed(String)}.</p>
 *
 * <p><b>Failure.</b> A channel reports its own failure at most once, on the reading thread, and
 * stops delivering afterwards. The reason is a short English description suitable for a log line:
 * it never contains peer identity, addresses or payload bytes.</p>
 */
public interface MediaChannelListener {

    /**
     * Delivers the descriptor of a config epoch.
     *
     * <p>Called before the first access unit of the epoch, like the send side sends it.</p>
     *
     * @param descriptor descriptor of the epoch; never null
     * @param secure     whether the peer reported this stream as secure
     */
    void onDescriptor(StreamDescriptor descriptor, boolean secure);

    /**
     * Delivers one encoded access unit.
     *
     * @param accessUnit access unit in receive order; never null
     * @param arrivalNs  local monotonic arrival time in nanoseconds, or a marker for an unmeasured
     *                   arrival
     */
    void onAccessUnit(EncodedAccessUnit accessUnit, long arrivalNs);

    /**
     * Reports that this channel failed and will deliver nothing else.
     *
     * @param reason short English description of the failure; never null
     */
    void onChannelFailed(String reason);

    /**
     * Asks the receiver to abandon what it has queued and resume at a join point.
     *
     * <p>A datagram channel reports this when it had to drop a whole access unit, because the units
     * that reference it decode into garbage. The receiver runs it on the same thread that applies
     * access units, so the queued ones are discarded before the next unit is applied. A channel
     * whose transport cannot lose data never calls it.</p>
     */
    default void onCatchUp() {
    }
}
