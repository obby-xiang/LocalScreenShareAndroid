package com.obby.android.localscreenshare.transport;

import com.obby.android.localscreenshare.identity.tls.TlsMaterial;
import com.obby.android.localscreenshare.identity.tls.TlsNames;
import com.obby.android.localscreenshare.trust.PinningTrustManager;
import com.obby.android.localscreenshare.trust.TlsPinLookup;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

/**
 * TLS configuration of the media channel, rebuilt from the same material as the control plane.
 *
 * <p>The media socket is not a second identity: it presents the certificate the control stream
 * already proved, verifies the peer with the pin the control stream already checked, and enables
 * only the protocol versions both ends support. What the channel adds is the one-shot token of the
 * offer, which binds this very socket to this very call. A channel built here is therefore never
 * weaker than the stream it replaces.</p>
 *
 * <p><b>Protocols.</b> The enabled versions are the intersection of what the platform supports and
 * {@code TLSv1.3}/{@code TLSv1.2}; asking a socket to enable a version it does not implement
 * throws, which is why the intersection is computed instead of assigned.</p>
 */
public final class MediaTlsFactory {

    /**
     * Protocol versions the media channel may use, newest first.
     */
    private static final String[] PREFERRED_PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    private MediaTlsFactory() {
    }

    /**
     * Builds the context of a media channel server.
     *
     * @param material identity presented on the channel; required
     * @param pins     pin lookup of the client role, only used for the server-side trust decision;
     *                 required
     * @return a context that presents {@code material} and accepts any self-signed client
     *         certificate, binding identity above TLS like the control plane does
     * @throws GeneralSecurityException if the platform cannot build the context
     */
    public static SSLContext serverContext(TlsMaterial material, TlsPinLookup pins)
            throws GeneralSecurityException {
        if (material == null) {
            throw new IllegalArgumentException("material must not be null");
        }
        if (pins == null) {
            throw new IllegalArgumentException("pins must not be null");
        }
        return context(new KeyManager[]{material.getKeyManager()},
                new TrustManager[]{new PinningTrustManager(null, pins, true,
                        PinningTrustManager.Role.SERVER)});
    }

    /**
     * Builds the context of a media channel client.
     *
     * @param material         identity presented on the channel; required
     * @param expectedDeviceId device the offer came from, or null when it is not pinned
     * @param pins             pin lookup of the client role; required
     * @throws GeneralSecurityException if the platform cannot build the context
     */
    public static SSLContext clientContext(TlsMaterial material, String expectedDeviceId,
                                           TlsPinLookup pins) throws GeneralSecurityException {
        if (material == null) {
            throw new IllegalArgumentException("material must not be null");
        }
        if (pins == null) {
            throw new IllegalArgumentException("pins must not be null");
        }
        return context(new KeyManager[]{material.getKeyManager()},
                new TrustManager[]{new PinningTrustManager(expectedDeviceId, pins)});
    }

    /**
     * Configures the server side of one accepted socket.
     *
     * @param socket accepted TLS socket; required
     */
    public static void configureServerSocket(SSLSocket socket) {
        if (socket == null) {
            throw new IllegalArgumentException("socket must not be null");
        }
        socket.setUseClientMode(false);
        socket.setNeedClientAuth(true);
        socket.setEnabledProtocols(commonProtocols(socket.getSupportedProtocols()));
    }

    /**
     * Configures the client side of one connecting socket.
     *
     * @param socket connected TLS socket, before the handshake; required
     */
    public static void configureClientSocket(SSLSocket socket) {
        if (socket == null) {
            throw new IllegalArgumentException("socket must not be null");
        }
        socket.setUseClientMode(true);
        socket.setEnabledProtocols(commonProtocols(socket.getSupportedProtocols()));
        SSLParameters parameters = socket.getSSLParameters();
        // The certificate carries one name, whatever address the peer was discovered at; the same
        // authority the control plane overrides is therefore the one verified here.
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        parameters.setServerNames(
                new ArrayList<>(Arrays.asList(new SNIHostName(TlsNames.AUTHORITY))));
        socket.setSSLParameters(parameters);
    }

    /**
     * Returns the protocol versions both this class and the platform support.
     *
     * @param supported versions reported by a socket; required
     * @return the intersection, newest first
     * @throws IllegalArgumentException if the platform supports none of the preferred versions
     */
    public static String[] commonProtocols(String[] supported) {
        if (supported == null) {
            throw new IllegalArgumentException("supported must not be null");
        }
        List<String> common = new ArrayList<>(PREFERRED_PROTOCOLS.length);
        for (String preferred : PREFERRED_PROTOCOLS) {
            for (String candidate : supported) {
                if (preferred.equalsIgnoreCase(candidate)) {
                    common.add(preferred);
                    break;
                }
            }
        }
        if (common.isEmpty()) {
            throw new IllegalArgumentException("no supported TLS version in common");
        }
        return common.toArray(new String[0]);
    }

    private static SSLContext context(KeyManager[] keyManagers, TrustManager[] trustManagers)
            throws GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers, trustManagers, null);
        return context;
    }
}
