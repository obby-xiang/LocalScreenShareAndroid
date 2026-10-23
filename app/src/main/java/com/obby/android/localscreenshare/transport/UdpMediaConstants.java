package com.obby.android.localscreenshare.transport;

/**
 * Parameters of the datagram media channel.
 *
 * <p>Only the negotiation parameters live here so far; the datagram layout and its bounds arrive
 * with the channel itself, and they belong to this same domain so that no cross-domain constant
 * class has to grow.</p>
 */
public final class UdpMediaConstants {

    /**
     * How long the server waits for the client's answer before it offers the next candidate.
     *
     * <p>Every answer travels on the control plane, so the window can never be shorter than one
     * round trip of that stream plus the work the client does before answering: a datagram offer is
     * answered right after the bind write, but a loaded device has been measured at 949 ms from the
     * offer to the {@code READY} answer, and the control plane of that same pair reported a 0.93 s
     * round-trip p95. The former 1 s window could therefore not tell a slow answer from a missing
     * one: on the r5 device run it expired an offer whose {@code READY} was already on its way, and
     * the fallback that followed was rejected as {@code handshake_bad_channel} because its own
     * ticket was revoked under the client. Two seconds match the stream channel's window, so both
     * candidates get the same allowance; a call that must fall back waits one second longer, and it
     * keeps streaming on the control plane while it waits.</p>
     */
    public static final long OFFER_TIMEOUT_MS = 2_000L;

    /**
     * How long the client waits for the first datagram after it reported the channel ready, in
     * milliseconds.
     *
     * <p>A datagram channel has no acknowledgement of its own: the first datagram that arrives is
     * the proof that the bind reached the server, so a silent socket means the bind was lost and
     * the call falls back. That datagram cannot arrive before the server has received the
     * {@code READY} answer on the control stream, swapped the transport onto this channel and
     * asked the encoder for a join point, so the window has to cover a control-plane round trip,
     * the swap and one key frame - not just the local path from the bind write to the server's
     * bind listener. The former 300 ms covered only that local path and made a loaded device
     * report {@code udp_bind_timeout} on three of four attempts. Two seconds matches the ordering
     * of the offer window and leaves headroom for a slow encoder; the window applies to the first
     * datagram only, the channel has no timeout afterwards.</p>
     */
    public static final long FIRST_FRAME_TIMEOUT_MS = 2_000L;

    /**
     * Datagram size the client expects when the offer names none, in bytes.
     */
    public static final int DEFAULT_MTU = 1400;

    /**
     * Headroom of the receive buffer beyond the offered datagram size, in bytes.
     */
    public static final int RECEIVE_SLACK_BYTES = 512;

    private UdpMediaConstants() {
    }
}
