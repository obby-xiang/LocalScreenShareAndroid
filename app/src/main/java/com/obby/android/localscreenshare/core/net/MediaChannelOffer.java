package com.obby.android.localscreenshare.core.net;

import java.util.Arrays;

/**
 * One media channel offer, independent of the wire format.
 *
 * <p>The offer carries everything needed to bring a channel up: which transport to use, the
 * endpoint, the one-shot credential that binds the channel to this call, the per-direction keys of
 * the datagram tier and the two parameters a receiver needs to size its buffers. It is a value
 * object built by the server and read by the client, so both sides agree on the fields without
 * either of them depending on the protocol buffer definitions.</p>
 *
 * <p>Byte arrays are copied on the way in and out. An offer that names no endpoint (a build that
 * cannot bind the channel yet) carries an empty host, a zero port and empty keys, which a receiver
 * must treat as "not established" rather than as a usable endpoint.</p>
 */
public final class MediaChannelOffer {

    private final byte[] channelId;
    private final MediaTransportKind transport;
    private final String host;
    private final int port;
    private final boolean secure;
    private final byte[] token;
    private final byte[] keyS2c;
    private final byte[] keyC2s;
    private final byte[] nonceSalt;
    private final int mtu;
    private final int holdMs;

    private MediaChannelOffer(Builder builder) {
        this.channelId = builder.channelId;
        this.transport = builder.transport;
        this.host = builder.host;
        this.port = builder.port;
        this.secure = builder.secure;
        this.token = builder.token;
        this.keyS2c = builder.keyS2c;
        this.keyC2s = builder.keyC2s;
        this.nonceSalt = builder.nonceSalt;
        this.mtu = builder.mtu;
        this.holdMs = builder.holdMs;
    }

    /**
     * Starts an offer for one channel.
     *
     * @param channelId identifier the answer must echo back; 16 bytes, required
     * @param transport channel the offer is for; required
     * @return a builder whose other fields default to "not established"
     * @throws IllegalArgumentException if an argument is null or the id has the wrong length
     */
    public static Builder builder(byte[] channelId, MediaTransportKind transport) {
        return new Builder(channelId, transport);
    }

    /**
     * Returns a copy of the channel id.
     */
    public byte[] getChannelId() {
        return Arrays.copyOf(channelId, channelId.length);
    }

    /**
     * Returns the channel id as lowercase hexadecimal.
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
     * Returns the transport the channel uses.
     */
    public MediaTransportKind getTransport() {
        return transport;
    }

    /**
     * Returns the host the client should connect to, or an empty string when the offer names none.
     */
    public String getHost() {
        return host;
    }

    /**
     * Returns the port the client should connect to, or zero when the offer names none.
     */
    public int getPort() {
        return port;
    }

    /**
     * Returns whether the server expects the channel to be secured.
     */
    public boolean isSecure() {
        return secure;
    }

    /**
     * Returns a copy of the one-shot credential the client presents when it opens the channel.
     */
    public byte[] getToken() {
        return Arrays.copyOf(token, token.length);
    }

    /**
     * Returns a copy of the server-to-client key of the datagram tier, empty when unused.
     */
    public byte[] getKeyS2c() {
        return Arrays.copyOf(keyS2c, keyS2c.length);
    }

    /**
     * Returns a copy of the client-to-server key of the datagram tier, empty when unused.
     */
    public byte[] getKeyC2s() {
        return Arrays.copyOf(keyC2s, keyC2s.length);
    }

    /**
     * Returns a copy of the nonce salt of the datagram tier, empty when unused.
     */
    public byte[] getNonceSalt() {
        return Arrays.copyOf(nonceSalt, nonceSalt.length);
    }

    /**
     * Returns the largest datagram the server will send, or zero when unused.
     */
    public int getMtu() {
        return mtu;
    }

    /**
     * Returns how long the receiver may hold an incomplete access unit, or zero when unused.
     */
    public int getHoldMs() {
        return holdMs;
    }

    /**
     * Returns whether the offer names an endpoint a client could actually reach.
     */
    public boolean isEndpointPresent() {
        return host != null && !host.isEmpty() && port > 0;
    }

    @Override
    public String toString() {
        return "MediaChannelOffer{channelId=" + getChannelIdHex() + ", transport="
                + transport.getLabel() + ", port=" + port + ", secure=" + secure + '}';
    }

    /**
     * Builds one offer.
     */
    public static final class Builder {

        private final byte[] channelId;

        private final MediaTransportKind transport;

        private String host = "";

        private int port;

        private boolean secure;

        private byte[] token = new byte[0];

        private byte[] keyS2c = new byte[0];

        private byte[] keyC2s = new byte[0];

        private byte[] nonceSalt = new byte[0];

        private int mtu;

        private int holdMs;

        private Builder(byte[] channelId, MediaTransportKind transport) {
            if (channelId == null
                    || channelId.length != MediaChannelTicket.CHANNEL_ID_BYTES) {
                throw new IllegalArgumentException(
                        "channelId must be " + MediaChannelTicket.CHANNEL_ID_BYTES + " bytes");
            }
            if (transport == null) {
                throw new IllegalArgumentException("transport must not be null");
            }
            this.channelId = Arrays.copyOf(channelId, channelId.length);
            this.transport = transport;
        }

        /**
         * Sets the endpoint host; null is stored as an empty host.
         */
        public Builder host(String host) {
            this.host = host == null ? "" : host;
            return this;
        }

        /**
         * Sets the endpoint port.
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * Sets whether the channel is expected to be secured.
         */
        public Builder secure(boolean secure) {
            this.secure = secure;
            return this;
        }

        /**
         * Sets the one-shot credential; null is stored as an empty token.
         */
        public Builder token(byte[] token) {
            this.token = token == null ? new byte[0] : Arrays.copyOf(token, token.length);
            return this;
        }

        /**
         * Sets the server-to-client key; null is stored as an empty key.
         */
        public Builder keyS2c(byte[] keyS2c) {
            this.keyS2c = keyS2c == null ? new byte[0] : Arrays.copyOf(keyS2c, keyS2c.length);
            return this;
        }

        /**
         * Sets the client-to-server key; null is stored as an empty key.
         */
        public Builder keyC2s(byte[] keyC2s) {
            this.keyC2s = keyC2s == null ? new byte[0] : Arrays.copyOf(keyC2s, keyC2s.length);
            return this;
        }

        /**
         * Sets the nonce salt of the datagram tier; null is stored as an empty salt.
         */
        public Builder nonceSalt(byte[] nonceSalt) {
            this.nonceSalt = nonceSalt == null
                    ? new byte[0] : Arrays.copyOf(nonceSalt, nonceSalt.length);
            return this;
        }

        /**
         * Sets the largest datagram of the channel.
         */
        public Builder mtu(int mtu) {
            this.mtu = mtu;
            return this;
        }

        /**
         * Sets how long the receiver may hold an incomplete access unit.
         */
        public Builder holdMs(int holdMs) {
            this.holdMs = holdMs;
            return this;
        }

        /**
         * Builds the immutable offer.
         */
        public MediaChannelOffer build() {
            return new MediaChannelOffer(this);
        }
    }
}
