package com.obby.android.localscreenshare.trust;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.identity.tls.TlsCertificate;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Objects;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Client-side trust manager of the discovery handshake: encrypts, observes, does not trust.
 *
 * <p>Discovery calls a peer before the user has selected it, so there is nothing to compare a pin
 * against yet; a strict manager here would turn every first sighting into a failed handshake. This
 * manager therefore accepts a single self-signed certificate and reports it, still refusing an
 * empty or multi-certificate chain. The certificate is not a trust decision — the reported SPKI is
 * what the {@link RegisterResponse} guard compares against the claimed device id, and what the
 * media channel later pins.</p>
 *
 * <p>The listener runs on a gRPC transport thread and must only record the value.</p>
 */
public final class DiscoveryTrustManager extends X509ExtendedTrustManager {

    /**
     * Reason reported when the peer chain is not a single certificate.
     */
    public static final String REASON_INVALID_CHAIN = "invalid_chain";

    /**
     * Receives the certificate observed during the handshake.
     */
    public interface Listener {
        /**
         * Called once per handshake with the peer certificate.
         */
        void onPeerCertificate(@NonNull TlsCertificate certificate);
    }

    @NonNull
    private final Listener mListener;

    /**
     * Creates a manager that reports the observed certificate to {@code listener}.
     */
    public DiscoveryTrustManager(@NonNull final Listener listener) {
        mListener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] chain, final String authType)
        throws CertificateException {
        throw new CertificateException("client_auth_unsupported");
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] chain, final String authType,
                                   final Socket socket) throws CertificateException {
        throw new CertificateException("client_auth_unsupported");
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] chain, final String authType,
                                   final SSLEngine engine) throws CertificateException {
        throw new CertificateException("client_auth_unsupported");
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] chain, final String authType)
        throws CertificateException {
        observe(chain);
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] chain, final String authType,
                                   final Socket socket) throws CertificateException {
        observe(chain);
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] chain, final String authType,
                                   final SSLEngine engine) throws CertificateException {
        observe(chain);
    }

    @NonNull
    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }

    private void observe(@Nullable final X509Certificate[] chain) throws CertificateException {
        if (chain == null || chain.length != 1 || chain[0] == null) {
            throw new CertificateException(REASON_INVALID_CHAIN);
        }
        mListener.onPeerCertificate(TlsCertificate.fromCertificate(chain[0]));
    }
}
