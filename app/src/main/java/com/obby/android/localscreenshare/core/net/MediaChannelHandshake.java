package com.obby.android.localscreenshare.core.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Greeting exchanged on a fresh media channel socket before any frame.
 *
 * <p>The channel is opened by the client, so it is the client that proves which call the socket
 * belongs to: the hello carries the channel id of an outstanding offer and the one-shot token that
 * came with it. The server answers with a sixteen-byte acknowledgement that says whether the
 * socket was bound, and the channel only starts carrying frames after that. Both directions are
 * fixed size, so a wrong magic, an unknown version or a bad length is a protocol violation rather
 * than something to resynchronize on.</p>
 *
 * <p><b>Why a greeting and no frame magic.</b> The handshake already proves the endpoint: the
 * socket is authenticated by the certificate before these bytes are read, and the token binds it to
 * one call. A frame-level magic would then only cost a word per frame without adding any check the
 * handshake does not already perform.</p>
 */
public final class MediaChannelHandshake {

    /**
     * Version of the handshake this class writes and reads.
     */
    public static final int VERSION = 1;

    /**
     * Length of the client hello, in bytes.
     */
    public static final int HELLO_BYTES = 56;

    /**
     * Length of the server acknowledgement, in bytes.
     */
    public static final int ACK_BYTES = 16;

    /**
     * Wire code of the dedicated stream channel.
     */
    public static final int TRANSPORT_TCP = 1;

    /**
     * Wire code of the datagram channel.
     */
    public static final int TRANSPORT_UDP = 2;

    private static final byte[] HELLO_MAGIC = {'L', 'S', 'M', '1'};

    private static final byte[] ACK_MAGIC = {'L', 'S', 'A', '1'};

    private MediaChannelHandshake() {
    }

    /**
     * Outcome the server reports for a hello.
     */
    public enum Status {

        /**
         * The socket is bound to the call; frames follow.
         */
        OK(0),

        /**
         * The channel id is unknown, expired or revoked.
         */
        BAD_CHANNEL(1),

        /**
         * The token does not match the one issued with the offer.
         */
        BAD_TOKEN(2),

        /**
         * The certificate identity differs from the one the offer was sent to.
         */
        PEER_MISMATCH(3),

        /**
         * The call already has a channel.
         */
        BUSY(4);

        private final int code;

        Status(int code) {
            this.code = code;
        }

        /**
         * Returns the wire code of this status.
         */
        public int getCode() {
            return code;
        }

        /**
         * Resolves a wire code, or null when the value is unknown.
         */
        public static Status fromCode(int code) {
            for (Status status : values()) {
                if (status.code == code) {
                    return status;
                }
            }
            return null;
        }
    }

    /**
     * Decoded client hello.
     */
    public static final class Hello {

        private final MediaTransportKind transport;
        private final byte[] channelId;
        private final byte[] token;

        private Hello(MediaTransportKind transport, byte[] channelId, byte[] token) {
            this.transport = transport;
            this.channelId = channelId;
            this.token = token;
        }

        /**
         * Returns the transport the client opened.
         */
        public MediaTransportKind getTransport() {
            return transport;
        }

        /**
         * Returns a copy of the channel id.
         */
        public byte[] getChannelId() {
            return channelId.clone();
        }

        /**
         * Returns the channel id in lowercase hexadecimal.
         */
        public String getChannelIdHex() {
            StringBuilder out = new StringBuilder(channelId.length * 2);
            for (byte value : channelId) {
                out.append(Character.forDigit((value >> 4) & 0x0f, 16));
                out.append(Character.forDigit(value & 0x0f, 16));
            }
            return out.toString();
        }

        /**
         * Returns a copy of the token.
         */
        public byte[] getToken() {
            return token.clone();
        }
    }

    /**
     * Encodes the client hello.
     *
     * @param transport socket transport; only {@link MediaTransportKind#TCP} and
     *                  {@link MediaTransportKind#UDP} are representable
     * @param channelId channel id of the offer; 16 bytes, required
     * @param token     token that came with the offer; 32 bytes, required
     * @return the complete hello
     * @throws IllegalArgumentException if an argument is null or out of range
     */
    public static byte[] encodeHello(MediaTransportKind transport, byte[] channelId,
                                     byte[] token) {
        if (channelId == null || channelId.length != MediaChannelTicket.CHANNEL_ID_BYTES) {
            throw new IllegalArgumentException(
                    "channelId must be " + MediaChannelTicket.CHANNEL_ID_BYTES + " bytes");
        }
        if (token == null || token.length != MediaChannelTicket.TOKEN_BYTES) {
            throw new IllegalArgumentException(
                    "token must be " + MediaChannelTicket.TOKEN_BYTES + " bytes");
        }
        int transportCode = transportCode(transport);
        ByteBuffer out = ByteBuffer.allocate(HELLO_BYTES);
        out.put(HELLO_MAGIC);
        out.put((byte) VERSION);
        out.put((byte) transportCode);
        out.putShort((short) 0);
        out.put(channelId);
        out.put(token);
        return out.array();
    }

    /**
     * Decodes the client hello.
     *
     * @param bytes received bytes; must be exactly {@link #HELLO_BYTES} long
     * @return the decoded hello
     * @throws IOException if the magic, version, transport or length is wrong
     */
    public static Hello decodeHello(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length != HELLO_BYTES) {
            throw new IOException("hello must be " + HELLO_BYTES + " bytes");
        }
        ByteBuffer in = ByteBuffer.wrap(bytes);
        byte[] magic = new byte[4];
        in.get(magic);
        if (!Arrays.equals(magic, HELLO_MAGIC)) {
            throw new IOException("bad hello magic");
        }
        int version = in.get() & 0xff;
        if (version != VERSION) {
            throw new IOException("unsupported hello version: " + version);
        }
        int transportCode = in.get() & 0xff;
        in.getShort();
        byte[] channelId = new byte[MediaChannelTicket.CHANNEL_ID_BYTES];
        in.get(channelId);
        byte[] token = new byte[MediaChannelTicket.TOKEN_BYTES];
        in.get(token);
        return new Hello(transportOf(transportCode), channelId, token);
    }

    /**
     * Encodes the server acknowledgement.
     *
     * @param status outcome to report; required
     * @return the complete acknowledgement
     * @throws IllegalArgumentException if {@code status} is null
     */
    public static byte[] encodeAck(Status status) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        ByteBuffer out = ByteBuffer.allocate(ACK_BYTES);
        out.put(ACK_MAGIC);
        out.put((byte) VERSION);
        out.put((byte) status.getCode());
        out.put(new byte[10]);
        return out.array();
    }

    /**
     * Decodes the server acknowledgement.
     *
     * @param bytes received bytes; must be exactly {@link #ACK_BYTES} long
     * @return the reported status, or null when the code is unknown
     * @throws IOException if the magic, version or length is wrong
     */
    public static Status decodeAck(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length != ACK_BYTES) {
            throw new IOException("ack must be " + ACK_BYTES + " bytes");
        }
        ByteBuffer in = ByteBuffer.wrap(bytes);
        byte[] magic = new byte[4];
        in.get(magic);
        if (!Arrays.equals(magic, ACK_MAGIC)) {
            throw new IOException("bad ack magic");
        }
        int version = in.get() & 0xff;
        if (version != VERSION) {
            throw new IOException("unsupported ack version: " + version);
        }
        return Status.fromCode(in.get() & 0xff);
    }

    private static int transportCode(MediaTransportKind transport) {
        if (transport == MediaTransportKind.TCP) {
            return TRANSPORT_TCP;
        }
        if (transport == MediaTransportKind.UDP) {
            return TRANSPORT_UDP;
        }
        throw new IllegalArgumentException("transport has no socket code: " + transport);
    }

    private static MediaTransportKind transportOf(int code) throws IOException {
        if (code == TRANSPORT_TCP) {
            return MediaTransportKind.TCP;
        }
        if (code == TRANSPORT_UDP) {
            return MediaTransportKind.UDP;
        }
        throw new IOException("unsupported transport: " + code);
    }
}
