package com.obby.android.localscreenshare.core.net;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * First datagram a client sends on a datagram channel: it binds the socket to one call.
 *
 * <p>A datagram channel has no handshake stream, so the first packet doubles as one. It carries the
 * channel id in the clear - the server needs it to find the keys - and, sealed with the
 * client-to-server key of that very channel, the token of the offer plus a header whose fragment
 * count is the sentinel {@code 0}. The token is what proves the sender is the client the offer was
 * made to; the key is what proves it is the same call, and neither travels in the clear.</p>
 *
 * <pre>
 * plaintext prefix
 * 0   16  channelId
 * 16  8   wireSeq (the nonce suffix, as on every datagram)
 * sealed body (AES-256-GCM under the client-to-server key, AAD = wireSeq)
 * 0   28  datagram header with fragmentCount = 0
 * 28  32  token of the offer
 * trailing 16 B authentication tag
 * </pre>
 *
 * <p>The server evaluates the source address only after both proofs pass: it binds the channel to
 * the address the first authenticated packet came from, and drops everything else before spending a
 * decryption on it.</p>
 */
public final class UdpBindPacket {

    /**
     * Length of the channel id, in bytes.
     */
    public static final int CHANNEL_ID_BYTES = 16;

    /**
     * Length of the clear prefix, in bytes: channel id and sequence number.
     */
    public static final int PREFIX_BYTES = CHANNEL_ID_BYTES + 8;

    /**
     * Length of the token carried inside the sealed body, in bytes.
     */
    public static final int TOKEN_BYTES = MediaChannelTicket.TOKEN_BYTES;

    /**
     * Length of the whole bind datagram, in bytes.
     */
    public static final int BIND_BYTES = PREFIX_BYTES + UdpMediaPacketCodec.HEADER_BYTES
            + TOKEN_BYTES + DatagramCipher.TAG_BYTES;

    private UdpBindPacket() {
    }

    /**
     * Encodes one bind datagram.
     *
     * @param channelId channel the client is binding; 16 bytes, required
     * @param token     token of the offer; 32 bytes, required
     * @param cipher    cipher of the client-to-server direction; required
     * @param wireSeq   sequence number of this datagram, unique per key
     * @return the datagram
     * @throws IllegalArgumentException if a length is wrong or an argument is null
     */
    public static byte[] encode(byte[] channelId, byte[] token, DatagramCipher cipher,
                                long wireSeq) {
        if (channelId == null || channelId.length != CHANNEL_ID_BYTES) {
            throw new IllegalArgumentException("channelId must be " + CHANNEL_ID_BYTES + " bytes");
        }
        if (token == null || token.length != TOKEN_BYTES) {
            throw new IllegalArgumentException("token must be " + TOKEN_BYTES + " bytes");
        }
        if (cipher == null) {
            throw new IllegalArgumentException("cipher must not be null");
        }
        ByteBuffer body = ByteBuffer.allocate(UdpMediaPacketCodec.HEADER_BYTES + TOKEN_BYTES);
        // Version one, every flag clear, fragment count zero: the sentinel of a bind packet.
        body.put((byte) UdpMediaPacketCodec.VERSION_FLAGS);
        body.put((byte) 0);
        body.putShort((short) 0);
        body.putInt(0);
        body.putLong(0L);
        body.putInt(0);
        body.putShort((short) 0);
        body.putShort((short) 0);
        body.putInt(0);
        body.put(token);
        byte[] ciphertext = cipher.encrypt(wireSeq, body.array());
        ByteBuffer datagram = ByteBuffer.allocate(PREFIX_BYTES + ciphertext.length);
        datagram.put(channelId);
        datagram.putLong(wireSeq);
        datagram.put(ciphertext);
        return datagram.array();
    }

    /**
     * Returns the channel id a datagram claims, without decrypting anything.
     *
     * @param datagram received bytes; may be null or short
     * @return the channel id, or null when the datagram is too short to carry one
     */
    public static byte[] peekChannelId(byte[] datagram) {
        if (datagram == null || datagram.length < CHANNEL_ID_BYTES) {
            return null;
        }
        return Arrays.copyOf(datagram, CHANNEL_ID_BYTES);
    }

    /**
     * Returns the sequence number a datagram claims, without decrypting anything.
     *
     * @param datagram received bytes; may be null or short
     * @return the sequence number, or zero when the datagram is too short to carry one
     */
    public static long peekWireSeq(byte[] datagram) {
        if (datagram == null || datagram.length < PREFIX_BYTES) {
            return 0L;
        }
        ByteBuffer buffer = ByteBuffer.wrap(datagram, CHANNEL_ID_BYTES, 8);
        return buffer.getLong();
    }

    /**
     * Decodes and authenticates one bind datagram.
     *
     * @param datagram received bytes; required
     * @param cipher   cipher of the client-to-server direction of the claimed channel; required
     * @return the decoded request
     * @throws DatagramCipher.AuthenticationException if the datagram did not authenticate
     * @throws IOException                            if the layout is malformed
     */
    public static BindRequest decode(byte[] datagram, DatagramCipher cipher) throws IOException {
        if (datagram == null || cipher == null) {
            throw new IllegalArgumentException("datagram and cipher must not be null");
        }
        if (datagram.length != BIND_BYTES) {
            throw new EOFException("bind datagram must be " + BIND_BYTES + " bytes");
        }
        ByteBuffer prefix = ByteBuffer.wrap(datagram, 0, PREFIX_BYTES);
        byte[] channelId = new byte[CHANNEL_ID_BYTES];
        prefix.get(channelId);
        long wireSeq = prefix.getLong();
        byte[] ciphertext = new byte[datagram.length - PREFIX_BYTES];
        System.arraycopy(datagram, PREFIX_BYTES, ciphertext, 0, ciphertext.length);
        byte[] plaintext = cipher.decrypt(wireSeq, ciphertext);
        ByteBuffer body = ByteBuffer.wrap(plaintext);
        int flags = body.get() & 0xff;
        body.get();
        int fragmentCount = body.getShort() & 0xffff;
        if ((flags & 0xc0) != UdpMediaPacketCodec.VERSION_FLAGS) {
            throw new IOException("unsupported bind version");
        }
        if (fragmentCount != 0) {
            throw new IOException("bind datagram must use the zero fragment sentinel");
        }
        body.position(UdpMediaPacketCodec.HEADER_BYTES);
        byte[] token = new byte[TOKEN_BYTES];
        body.get(token);
        return new BindRequest(channelId, token);
    }

    /**
     * Decoded bind request: the channel it claims and the token that proves it.
     */
    public static final class BindRequest {

        private final byte[] channelId;

        private final byte[] token;

        private BindRequest(byte[] channelId, byte[] token) {
            this.channelId = channelId;
            this.token = token;
        }

        /**
         * Returns the channel id the datagram claims.
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
         * Returns the token the datagram carried.
         */
        public byte[] getToken() {
            return token.clone();
        }
    }
}
