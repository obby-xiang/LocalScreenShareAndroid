package com.obby.android.localscreenshare.core.metrics;

/**
 * Metric keys of the media channels, kept in one domain holder.
 *
 * <p>They answer "which channel carried the frames, and how healthy was it": the transport counters
 * of the datagram tier ({@code udp.*}), the fragment and switch counters of any dedicated channel
 * ({@code tx.frag}, {@code tx.chanSwitch}) and the frame counters reserved for the stream channel
 * ({@code tcp.*}). The channel itself is printed as {@code chan} - that key lives in
 * {@link MetricNames} because the per-session line already owns it - and a channel label is a
 * constant string, so printing it allocates nothing.</p>
 *
 * <p><b>Omission.</b> A key is printed only when something measured it: an unmeasured tier prints
 * nothing rather than zeros, so a gRPC-only session and a UDP session never look comparable when
 * they are not. The keys below without a producer in this version are registered so the log
 * vocabulary is fixed before a producer exists - {@code tcp.frame} and {@code tcp.frameDrop} have
 * no counter yet, and a future batch adds them without renaming anything.
 * {@link #UDP_LATE} and {@link #UDP_AU_DROPPED} have left that group: the reassembler counts the
 * fragments it refuses after delivery or abandonment, and reports a unit discarded because a newer
 * one was delivered. {@link #UDP_IDR_REQUEST} left it earlier with the join-point counter, and
 * {@link #UDP_GAP}, {@link #UDP_HOLD} and {@link #UDP_AU_TIMEOUT} join the printed block with their
 * own producers in the receiver.</p>
 */
public final class MediaMetricNames {

    /**
     * Datagrams sent by a media channel, one per fragment of an access unit.
     */
    public static final String TX_FRAGMENT = "tx.frag";

    /**
     * Times a send session moved to a different media channel.
     */
    public static final String TX_CHANNEL_SWITCH = "tx.chanSwitch";

    /**
     * Frames written by the stream channel.
     */
    public static final String TCP_FRAME = "tcp.frame";

    /**
     * Frames the stream channel refused to write.
     */
    public static final String TCP_FRAME_DROP = "tcp.frameDrop";

    /**
     * Datagrams received on a media channel.
     */
    public static final String UDP_RECEIVED = "udp.recv";

    /**
     * Datagram sequence numbers the receiver never saw.
     */
    public static final String UDP_LOST = "udp.lost";

    /**
     * Datagrams the receiver already had.
     */
    public static final String UDP_DUPLICATE = "udp.dup";

    /**
     * Datagrams that arrived out of order but were still usable.
     */
    public static final String UDP_REORDERED = "udp.reorder";

    /**
     * Fragments that arrived after their access unit was delivered or abandoned.
     *
     * <p>Both endings are covered, because neither can still produce a frame: a delivered unit is
     * gone, and an abandoned one lost fragments that never arrive. Rebuilding an abandoned unit from
     * its residue used to count it again - this key is the evidence that the residue is only late.</p>
     */
    public static final String UDP_LATE = "udp.late";

    /**
     * Interval between two consecutive fragments of one access unit.
     */
    public static final String UDP_GAP = "udp.gap";

    /**
     * Hold-up currently applied to an unfinished access unit, in milliseconds.
     */
    public static final String UDP_HOLD = "udp.hold";

    /**
     * Access units abandoned because their last fragment fell silent for the hold-up.
     */
    public static final String UDP_AU_TIMEOUT = "udp.auTimeout";

    /**
     * Access units abandoned because at least one fragment never arrived.
     */
    public static final String UDP_AU_INCOMPLETE = "udp.auIncomplete";

    /**
     * Access units discarded whole because a newer unit was delivered first.
     */
    public static final String UDP_AU_DROPPED = "udp.auDrop";

    /**
     * Datagrams refused by authentication.
     */
    public static final String UDP_AUTH_FAILURE = "udp.authFail";

    /**
     * Key-frame requests issued because of datagram loss.
     */
    public static final String UDP_IDR_REQUEST = "udp.idrReq";

    private MediaMetricNames() {
    }
}
