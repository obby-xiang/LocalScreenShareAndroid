package com.obby.android.localscreenshare.core.net;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * One-shot credential that binds a media channel to the stream call that offered it.
 *
 * <p>The server creates a ticket when it offers a channel and keeps it until the channel is
 * established, the offer times out or the call ends. The client echoes the channel id with its
 * answer and presents the token when it opens the channel, so a channel socket can only join the
 * call that is waiting for exactly that socket: the id is public, the token is not.</p>
 *
 * <p><b>One shot.</b> {@link #consume(byte[])} compares the presented token with
 * {@link MessageDigest#isEqual(byte[], byte[])} - a comparison whose duration does not depend on
 * where the bytes differ - and accepts it at most once. A replay of the same token, a wrong token,
 * a token of the wrong length and a token presented after the ticket expired are all refused. The
 * ticket is refused after {@link #getTtlMs()} milliseconds from creation, which is why a leaked
 * token is only useful inside the window of the offer it belongs to.</p>
 *
 * <p>Byte arrays are copied on the way in and on the way out, so a caller can never mutate the
 * credential of a live ticket.</p>
 */
public final class MediaChannelTicket {

    /**
     * Default lifetime of an offer, matching the window the server waits for an answer.
     */
    public static final long DEFAULT_TTL_MS = 10_000L;

    /**
     * Length of the channel id, in bytes.
     */
    public static final int CHANNEL_ID_BYTES = 16;

    /**
     * Length of the token, in bytes.
     */
    public static final int TOKEN_BYTES = 32;

    private final byte[] channelId;
    private final byte[] token;
    private final MediaTransportKind transport;
    private final String peerDeviceId;
    private final long createdAtMs;
    private final long ttlMs;

    private boolean consumed;

    /**
     * Creates a ticket with explicit bytes.
     *
     * @param channelId    identifier the client echoes back; 16 bytes, required
     * @param token        credential the client presents; 32 bytes, required
     * @param transport    channel this ticket was issued for; required
     * @param peerDeviceId device id of the peer the offer was sent to, or null when the call has
     *                     no proven identity
     * @param createdAtMs  creation time on the injected clock
     * @param ttlMs        lifetime in milliseconds; must not be negative
     * @throws IllegalArgumentException if an argument is null or a length is wrong
     */
    public MediaChannelTicket(byte[] channelId, byte[] token, MediaTransportKind transport,
                              String peerDeviceId, long createdAtMs, long ttlMs) {
        if (channelId == null || channelId.length != CHANNEL_ID_BYTES) {
            throw new IllegalArgumentException("channelId must be " + CHANNEL_ID_BYTES + " bytes");
        }
        if (token == null || token.length != TOKEN_BYTES) {
            throw new IllegalArgumentException("token must be " + TOKEN_BYTES + " bytes");
        }
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        if (ttlMs < 0L) {
            throw new IllegalArgumentException("ttlMs must not be negative");
        }
        this.channelId = Arrays.copyOf(channelId, channelId.length);
        this.token = Arrays.copyOf(token, token.length);
        this.transport = transport;
        this.peerDeviceId = peerDeviceId;
        this.createdAtMs = createdAtMs;
        this.ttlMs = ttlMs;
    }

    /**
     * Issues a ticket with a fresh id and token from the shared secure random source.
     *
     * @param transport    channel to issue for; required
     * @param peerDeviceId device id of the peer, or null
     * @param nowMs        creation time on the caller's clock
     * @return a ticket that has not been consumed yet
     * @throws IllegalArgumentException if {@code transport} is null
     */
    public static MediaChannelTicket issue(MediaTransportKind transport, String peerDeviceId,
                                           long nowMs) {
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        byte[] channelId = new byte[CHANNEL_ID_BYTES];
        byte[] token = new byte[TOKEN_BYTES];
        RandomHolder.INSTANCE.nextBytes(channelId);
        RandomHolder.INSTANCE.nextBytes(token);
        return new MediaChannelTicket(channelId, token, transport, peerDeviceId, nowMs,
                DEFAULT_TTL_MS);
    }

    /**
     * Returns a copy of the channel id.
     */
    public byte[] getChannelId() {
        return Arrays.copyOf(channelId, channelId.length);
    }

    /**
     * Returns the channel id as lowercase hexadecimal, the form used in logs and matching.
     */
    public String getChannelIdHex() {
        StringBuilder out = new StringBuilder(CHANNEL_ID_BYTES * 2);
        for (byte value : channelId) {
            out.append(Character.forDigit((value >> 4) & 0x0f, 16));
            out.append(Character.forDigit(value & 0x0f, 16));
        }
        return out.toString();
    }

    /**
     * Returns a copy of the token, so a caller can hand it to the channel it opens.
     */
    public byte[] getToken() {
        return Arrays.copyOf(token, token.length);
    }

    /**
     * Returns the channel this ticket was issued for.
     */
    public MediaTransportKind getTransport() {
        return transport;
    }

    /**
     * Returns the device id of the peer the offer was sent to, or null when the call has none.
     */
    public String getPeerDeviceId() {
        return peerDeviceId;
    }

    /**
     * Returns the creation time on the injected clock, in milliseconds.
     */
    public long getCreatedAtMs() {
        return createdAtMs;
    }

    /**
     * Returns the lifetime of this ticket, in milliseconds.
     */
    public long getTtlMs() {
        return ttlMs;
    }

    /**
     * Returns whether the ticket is past its lifetime at {@code nowMs}.
     *
     * @param nowMs current time on the same clock the ticket was created with
     */
    public boolean isExpired(long nowMs) {
        return nowMs - createdAtMs >= ttlMs;
    }

    /**
     * Returns whether the token was already accepted; a consumed ticket never accepts another.
     */
    public boolean isConsumed() {
        synchronized (this) {
            return consumed;
        }
    }

    /**
     * Consumes this ticket when the presented token matches and the ticket is still valid.
     *
     * <p>The comparison is constant time with respect to the token contents, so a caller cannot
     * learn the credential byte by byte from the answer. Success is exclusive: the first accepted
     * token consumes the ticket, and every later attempt returns false.</p>
     *
     * @param presented     token the peer presented; null, empty or of the wrong length is refused
     * @param nowMs         current time on the same clock the ticket was created with
     * @return true when this call consumed the ticket
     */
    public boolean consume(byte[] presented, long nowMs) {
        synchronized (this) {
            if (consumed || isExpired(nowMs)) {
                return false;
            }
            if (presented == null || presented.length != token.length) {
                return false;
            }
            if (!MessageDigest.isEqual(token, presented)) {
                return false;
            }
            consumed = true;
            return true;
        }
    }

    @Override
    public String toString() {
        return "MediaChannelTicket{channelId=" + getChannelIdHex() + ", transport="
                + transport.getLabel() + ", createdAtMs=" + createdAtMs + ", ttlMs=" + ttlMs
                + ", consumed=" + isConsumed() + '}';
    }

    /**
     * Holds the one secure random source of this process.
     */
    private static final class RandomHolder {

        private static final SecureRandom INSTANCE = new SecureRandom();

        private RandomHolder() {
        }
    }
}
