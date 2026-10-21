package com.obby.android.localscreenshare.identity.tls;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.identity.SpkiId;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Objects;

/**
 * A certificate together with the identity derived from its public key.
 *
 * <p>The identity is always {@code SHA-256(SPKI)}, never a hash of the certificate: the
 * certificate is regenerated per process with a fresh ECDSA signature, so its DER bytes change
 * while the key, and therefore the identity, stay the same. Both the short id and the fingerprint
 * are computed once here and shared by the pin policy, the trust manager and the UI.</p>
 *
 * <p>Instances are immutable. The certificate itself is a JDK value; the class has no Android
 * dependency and no I/O.</p>
 */
public final class TlsCertificate {

    @NonNull
    private final X509Certificate mCertificate;

    @NonNull
    private final byte[] mSpki;

    @NonNull
    private final String mDeviceId;

    @NonNull
    private final String mFingerprint;

    private TlsCertificate(@NonNull final X509Certificate certificate, @NonNull final byte[] spki) {
        mCertificate = certificate;
        mSpki = spki;
        mDeviceId = SpkiId.deviceId(spki);
        mFingerprint = SpkiId.fingerprint(spki);
    }

    /**
     * Wraps a certificate received from a peer or produced locally.
     *
     * @throws CertificateException if the certificate carries no encoded public key
     */
    @NonNull
    public static TlsCertificate fromCertificate(@NonNull final X509Certificate certificate)
        throws CertificateException {
        Objects.requireNonNull(certificate, "certificate");
        final byte[] spki = certificate.getPublicKey() == null
            ? null : certificate.getPublicKey().getEncoded();
        if (spki == null || spki.length == 0) {
            throw new CertificateException("certificate has no encoded public key");
        }
        return new TlsCertificate(certificate, spki);
    }

    /**
     * Parses a DER certificate and derives its identity.
     *
     * @throws CertificateException if the bytes are not an X.509 certificate with a public key
     */
    @NonNull
    public static TlsCertificate fromEncoded(@NonNull final byte[] der)
        throws CertificateException {
        Objects.requireNonNull(der, "der");
        final CertificateFactory factory = CertificateFactory.getInstance("X.509");
        final java.security.cert.Certificate parsed =
            factory.generateCertificate(new ByteArrayInputStream(der));
        if (!(parsed instanceof X509Certificate)) {
            throw new CertificateException("not an X.509 certificate");
        }
        return fromCertificate((X509Certificate) parsed);
    }

    /**
     * Returns the certificate this identity was derived from.
     */
    @NonNull
    public X509Certificate getCertificate() {
        return mCertificate;
    }

    /**
     * Returns the X.509 {@code SubjectPublicKeyInfo} encoding of the identity key.
     */
    @NonNull
    public byte[] getSpki() {
        return mSpki.clone();
    }

    /**
     * Returns the 26-character Crockford base32 device id derived from the SPKI.
     */
    @NonNull
    public String getDeviceId() {
        return mDeviceId;
    }

    /**
     * Returns the 43-character unpadded base64url fingerprint derived from the SPKI.
     */
    @NonNull
    public String getFingerprint() {
        return mFingerprint;
    }
}
