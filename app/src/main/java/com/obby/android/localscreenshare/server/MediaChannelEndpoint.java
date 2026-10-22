package com.obby.android.localscreenshare.server;

import com.obby.android.localscreenshare.core.net.MediaTransportKind;

/**
 * Where a media channel of this sharing session can be reached, or that it cannot be reached at
 * all.
 *
 * <p>An offer names an endpoint, but the endpoint is a property of the session rather than of the
 * call: one port is bound when sharing starts and every call of that session is offered it. An
 * unavailable endpoint (a listener that could not bind) makes the offer code skip the channel
 * entirely, so a call falls back to its carrier without the client ever seeing a broken offer.</p>
 */
public final class MediaChannelEndpoint {

    private final String host;

    private final int tcpPort;

    private final int udpPort;

    private final boolean secure;

    /**
     * Creates an endpoint with a stream channel only.
     *
     * @param host   address to advertise, or an empty string to mean "the address the control
     *               plane is already using"
     * @param port   bound stream port, or zero when no listener is running
     * @param secure whether the stream channel is TLS
     */
    public MediaChannelEndpoint(String host, int port, boolean secure) {
        this(host, port, 0, secure);
    }

    /**
     * Creates an endpoint with both channels.
     *
     * @param host       address to advertise, or an empty string for the control-plane address
     * @param tcpPort    bound stream port, or zero
     * @param udpPort    bound datagram port, or zero
     * @param secure     whether the stream channel is TLS
     */
    public MediaChannelEndpoint(String host, int tcpPort, int udpPort, boolean secure) {
        this.host = host == null ? "" : host;
        this.tcpPort = tcpPort;
        this.udpPort = udpPort;
        this.secure = secure;
    }

    /**
     * Returns an endpoint that no channel can be offered on.
     */
    public static MediaChannelEndpoint unavailable() {
        return new MediaChannelEndpoint("", 0, false);
    }

    /**
     * Returns the advertised host, empty when the control-plane address applies.
     */
    public String getHost() {
        return host;
    }

    /**
     * Returns the bound stream port, or zero when no stream listener is running.
     */
    public int getPort() {
        return tcpPort;
    }

    /**
     * Returns the bound datagram port, or zero when no datagram listener is running.
     */
    public int getUdpPort() {
        return udpPort;
    }

    /**
     * Returns the port of one channel, or zero when that channel has no listener.
     *
     * @param kind channel to ask for; a null kind returns zero
     */
    public int getPort(MediaTransportKind kind) {
        if (kind == MediaTransportKind.TCP) {
            return tcpPort;
        }
        if (kind == MediaTransportKind.UDP) {
            return udpPort;
        }
        return 0;
    }

    /**
     * Returns whether the channel is TLS.
     */
    public boolean isSecure() {
        return secure;
    }

    /**
     * Returns whether a stream channel can be offered on this endpoint.
     */
    public boolean isAvailable() {
        return tcpPort > 0;
    }

    /**
     * Returns whether the given channel can be offered on this endpoint.
     *
     * @param kind channel to ask for
     */
    public boolean isAvailable(MediaTransportKind kind) {
        return getPort(kind) > 0;
    }
}
