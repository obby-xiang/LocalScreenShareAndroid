package com.obby.android.localscreenshare.server;

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

    private final int port;

    private final boolean secure;

    /**
     * Creates an endpoint.
     *
     * @param host   address to advertise, or an empty string to mean "the address the control
     *               plane is already using"
     * @param port   bound port, or zero when no listener is running
     * @param secure whether the channel is TLS
     */
    public MediaChannelEndpoint(String host, int port, boolean secure) {
        this.host = host == null ? "" : host;
        this.port = port;
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
     * Returns the bound port, or zero when no listener is running.
     */
    public int getPort() {
        return port;
    }

    /**
     * Returns whether the channel is TLS.
     */
    public boolean isSecure() {
        return secure;
    }

    /**
     * Returns whether a channel can be offered on this endpoint.
     */
    public boolean isAvailable() {
        return port > 0;
    }
}
