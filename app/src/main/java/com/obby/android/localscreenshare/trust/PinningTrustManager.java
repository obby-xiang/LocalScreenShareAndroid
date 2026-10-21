package com.obby.android.localscreenshare.trust;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.identity.SpkiId;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Objects;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Trust manager that accepts a peer certificate only through a pin.
 *
 * <p>In the client role it applies {@link PinPolicy} to the single certificate the server
 * presented. In the server role it only proves that the client holds the private key of a
 * self-signed certificate: the server does not know whom the caller claims to be until HELLO
 * arrives, so identity binding happens above TLS, against this same certificate.</p>
 *
 * <p>Validity dates are deliberately not checked. Device clocks are unreliable, and the trust
 * anchor is the pin, not the date range; a certificate is regenerated per process anyway. The
 * check methods never block, never show UI and never read a file: they run on transport
 * threads, and the pin lookup is an in-memory comparison.</p>
 */
public final class PinningTrustManager extends X509ExtendedTrustManager {

    /**
     * The peer did not present a usable self-signed certificate.
     */
    public static final String REASON_INVALID_CHAIN = "invalid_chain";

    /**
     * No pin exists for the peer and the caller requires one.
     */
    public static final String REASON_PIN_UNKNOWN = "pin_unknown";

    /**
     * The peer fingerprint differs from the pinned one.
     */
    public static final String REASON_IDENTITY_CHANGED = "identity_changed";

    /**
     * The peer certificate does not match the expected device id.
     */
    public static final String REASON_IDENTITY_MISMATCH = "identity_mismatch";

    /**
     * Role the manager is used in.
     */
    public enum Role {
        /**
         * Verifies the server certificate against a pin.
         */
        CLIENT,
        /**
         * Accepts any self-signed client certificate; binding is done by the caller.
         */
        SERVER
    }

    @Nullable
    private final String mExpectedDeviceId;

    @NonNull
    private final TlsPinLookup mPins;

    private final boolean mIsPinnedRequired;

    @NonNull
    private final Role mRole;

    /**
     * Creates a client-role manager that requires an existing pin.
     */
    public PinningTrustManager(@Nullable final String expectedDeviceId,
                               @NonNull final TlsPinLookup pins) {
        this(expectedDeviceId, pins, true, Role.CLIENT);
    }

    /**
     * Creates a manager for one role.
     *
     * @param expectedDeviceId id selected in discovery, or null to skip self-consistency
     * @param pins             pin lookup, usually {@link TlsPinStore#get()}
     * @param requirePinned    whether a missing pin is a failure in the client role
     * @param role             client or server side of the handshake
     */
    public PinningTrustManager(@Nullable final String expectedDeviceId,
                               @NonNull final TlsPinLookup pins, final boolean requirePinned,
                               @NonNull final Role role) {
        mExpectedDeviceId = expectedDeviceId == null ? null : expectedDeviceId.trim();
        mPins = Objects.requireNonNull(pins, "pins");
        mIsPinnedRequired = requirePinned;
        mRole = Objects.requireNonNull(role, "role");
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] chain, final String authType)
        throws CertificateException {
        checkClientChain(chain);
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] chain, final String authType,
                                   final Socket socket) throws CertificateException {
        checkClientChain(chain);
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] chain, final String authType,
                                   final SSLEngine engine) throws CertificateException {
        checkClientChain(chain);
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] chain, final String authType)
        throws CertificateException {
        checkServerChain(chain);
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] chain, final String authType,
                                   final Socket socket) throws CertificateException {
        checkServerChain(chain);
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] chain, final String authType,
                                   final SSLEngine engine) throws CertificateException {
        checkServerChain(chain);
    }

    @NonNull
    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }

    private void checkServerChain(@Nullable final X509Certificate[] chain)
        throws CertificateException {
        if (mRole != Role.CLIENT) {
            throw new CertificateException("server_auth_unsupported");
        }
        final X509Certificate certificate = singleCertificate(chain);
        final byte[] spki = spkiOf(certificate);
        final TlsPin pin = mPins.find(SpkiId.deviceId(spki));
        final PinPolicy.Outcome outcome = PinPolicy.decide(mExpectedDeviceId, spki, pin);
        switch (outcome) {
            case MATCH:
                return;
            case UNKNOWN:
                if (!mIsPinnedRequired) {
                    return;
                }
                throw new CertificateException(REASON_PIN_UNKNOWN);
            case CHANGED:
                throw new CertificateException(REASON_IDENTITY_CHANGED);
            case IDENTITY_MISMATCH:
            default:
                throw new CertificateException(REASON_IDENTITY_MISMATCH);
        }
    }

    private void checkClientChain(@Nullable final X509Certificate[] chain)
        throws CertificateException {
        if (mRole != Role.SERVER) {
            throw new CertificateException("client_auth_unsupported");
        }
        final X509Certificate certificate = singleCertificate(chain);
        if (!certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())) {
            throw new CertificateException(REASON_INVALID_CHAIN);
        }
        try {
            certificate.verify(certificate.getPublicKey());
        } catch (CertificateException e) {
            throw e;
        } catch (Exception e) {
            throw new CertificateException(REASON_INVALID_CHAIN, e);
        }
    }

    @NonNull
    private static X509Certificate singleCertificate(@Nullable final X509Certificate[] chain)
        throws CertificateException {
        if (chain == null || chain.length != 1 || chain[0] == null) {
            throw new CertificateException(REASON_INVALID_CHAIN);
        }
        return chain[0];
    }

    @NonNull
    private static byte[] spkiOf(@NonNull final X509Certificate certificate)
        throws CertificateParsingException {
        final byte[] spki =
            certificate.getPublicKey() == null ? null : certificate.getPublicKey().getEncoded();
        if (spki == null || spki.length == 0) {
            throw new CertificateParsingException("certificate has no encoded public key");
        }
        return spki;
    }
}
