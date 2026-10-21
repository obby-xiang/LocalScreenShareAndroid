package com.obby.android.localscreenshare.trust;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.identity.tls.TlsCertificate;

import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/**
 * Maps a completed TLS session to the certificate identity of the peer.
 *
 * <p>Only the single self-signed certificate the peer presented is accepted: a chain would mean
 * some issuer relationship this project does not model. The session view is reduced to a
 * certificate array so the mapping can be tested without a real handshake.</p>
 */
public final class PeerIdentityMapper {

    private PeerIdentityMapper() {
    }

    /**
     * Returns the peer identity of a session, or null when the peer presented no certificate.
     */
    @Nullable
    public static TlsCertificate fromSession(@Nullable final SSLSession session) {
        if (session == null) {
            return null;
        }
        try {
            return fromChain(session.getPeerCertificates());
        } catch (SSLPeerUnverifiedException | CertificateException e) {
            return null;
        }
    }

    /**
     * Maps a peer certificate chain to its identity.
     *
     * @throws CertificateException if the chain is not exactly one X.509 certificate with a public
     *                              key
     */
    @NonNull
    public static TlsCertificate fromChain(@Nullable final Certificate[] chain)
        throws CertificateException {
        if (chain == null || chain.length != 1 || !(chain[0] instanceof X509Certificate)) {
            throw new CertificateException("peer chain must be one X.509 certificate");
        }
        return TlsCertificate.fromCertificate((X509Certificate) chain[0]);
    }
}
