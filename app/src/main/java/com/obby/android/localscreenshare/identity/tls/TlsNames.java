package com.obby.android.localscreenshare.identity.tls;

/**
 * Names shared by the certificate and the TLS endpoints.
 *
 * <p>Both the subject alternative name of the self-signed certificate and the authority the
 * client verifies are derived from one constant. A certificate whose SAN and the authority the
 * client checks could drift apart would fail the hostname check on every connection, so neither
 * side is allowed to spell the value itself.</p>
 */
public final class TlsNames {

    /**
     * Authority used as the certificate CN, its dNSName SAN and the client's override authority.
     */
    public static final String AUTHORITY = "lss.local";

    private TlsNames() {
    }
}
