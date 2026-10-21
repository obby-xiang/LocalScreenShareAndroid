package com.obby.android.localscreenshare.identity.tls;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.identity.DeviceIdentity;

import java.security.GeneralSecurityException;
import java.security.KeyPair;

/**
 * TLS material of this installation: the signed certificate and the key manager that presents it.
 *
 * <p>Material is derived from a {@link KeyPair} on demand and never persisted. Signing the
 * certificate costs one ECDSA operation, and keeping it out of storage removes the possibility of
 * a stale certificate that no longer matches the key. The identity still comes from the SPKI, so
 * regenerating the certificate does not change what peers compare.</p>
 *
 * <p>Instances are immutable and cheap to share between the server and the client.</p>
 */
public final class TlsMaterial {

    /**
     * Alias under which the identity key is presented to JSSE.
     */
    public static final String KEY_ALIAS = "lss-identity";

    @NonNull
    private final TlsCertificate mCertificate;

    @NonNull
    private final IdentityKeyAdapter mKeyAdapter;

    private TlsMaterial(@NonNull final TlsCertificate certificate,
                        @NonNull final IdentityKeyAdapter keyAdapter) {
        mCertificate = certificate;
        mKeyAdapter = keyAdapter;
    }

    /**
     * Signs a certificate with the private half of {@code keyPair} and packages the TLS material.
     *
     * @throws GeneralSecurityException if the key cannot be read or the certificate cannot be built
     * @throws IllegalArgumentException if the key pair is incomplete
     */
    @NonNull
    public static TlsMaterial fromKeyPair(@NonNull final KeyPair keyPair)
        throws GeneralSecurityException {
        if (keyPair == null || keyPair.getPublic() == null || keyPair.getPrivate() == null) {
            throw new IllegalArgumentException("keyPair must have both halves");
        }
        final byte[] der =
            SelfSignedCertificateEncoder.encode(keyPair.getPublic(), keyPair.getPrivate());
        final TlsCertificate certificate = TlsCertificate.fromEncoded(der);
        return new TlsMaterial(certificate, new IdentityKeyAdapter(KEY_ALIAS,
            certificate.getCertificate(), keyPair.getPrivate()));
    }

    /**
     * Returns the certificate and its derived identity.
     */
    @NonNull
    public TlsCertificate getCertificate() {
        return mCertificate;
    }

    /**
     * Returns the key manager that presents the certificate during a handshake.
     */
    @NonNull
    public IdentityKeyAdapter getKeyManager() {
        return mKeyAdapter;
    }

    /**
     * Returns the 26-character device id derived from the SPKI.
     */
    @NonNull
    public String getDeviceId() {
        return mCertificate.getDeviceId();
    }

    /**
     * Returns the 43-character fingerprint derived from the SPKI.
     */
    @NonNull
    public String getFingerprint() {
        return mCertificate.getFingerprint();
    }

    /**
     * Returns the device identity this material presents in a handshake.
     *
     * <p>Derived from the same SPKI the certificate carries, so a caller that announces, publishes
     * or registers an identity through this method can never name a key other than the one the
     * handshake proves. This is the value the announcement, the {@code ServerInfo}, the {@code
     * Register} self summary and the viewer {@code HELLO} have to use.</p>
     */
    @NonNull
    public DeviceIdentity toDeviceIdentity() {
        return DeviceIdentity.fromSpki(mCertificate.getSpki(), null);
    }
}
