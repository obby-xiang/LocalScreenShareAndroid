package com.obby.android.localscreenshare.identity.tls;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.Objects;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;

/**
 * Presents the identity key pair to JSSE under one fixed alias.
 *
 * <p>The identity private key never leaves the AndroidKeyStore, so it cannot be loaded from a PEM
 * pair; it reaches the handshake as a {@link PrivateKey} handle instead. This adapter returns that
 * handle and the self-signed certificate for the alias the handshake asks for, and nothing else.
 * Because it implements both the socket and the engine variants, the same instance works whether
 * the transport handshakes over {@code SSLSocket} or {@code SSLEngine}.</p>
 *
 * <p>Key types other than EC are not answered: the identity key is EC P-256 and offering the alias
 * for, say, RSA would make JSSE pick a certificate it cannot use. The issuer list is ignored,
 * because the certificate is self-signed and its issuer is itself.</p>
 */
public final class IdentityKeyAdapter extends X509ExtendedKeyManager {

    @NonNull
    private final String mAlias;

    @NonNull
    private final X509Certificate[] mChain;

    @NonNull
    private final PrivateKey mPrivateKey;

    /**
     * Creates the adapter for one key pair.
     *
     * @param alias        alias reported to JSSE
     * @param certificate  self-signed certificate of the key
     * @param privateKey   private key handle of the same pair
     */
    public IdentityKeyAdapter(@NonNull final String alias,
                              @NonNull final X509Certificate certificate,
                              @NonNull final PrivateKey privateKey) {
        mAlias = Objects.requireNonNull(alias, "alias");
        mChain = new X509Certificate[]{Objects.requireNonNull(certificate, "certificate")};
        mPrivateKey = Objects.requireNonNull(privateKey, "privateKey");
    }

    /**
     * Returns the alias of the identity key.
     */
    @NonNull
    public String getAlias() {
        return mAlias;
    }

    @Nullable
    @Override
    public String[] getClientAliases(final String keyType, final Principal[] issuers) {
        return isEc(keyType) ? new String[]{mAlias} : null;
    }

    @Nullable
    @Override
    public String chooseClientAlias(final String[] keyTypes, final Principal[] issuers,
                                    final Socket socket) {
        return chooseFrom(keyTypes);
    }

    @Nullable
    @Override
    public String chooseEngineClientAlias(final String[] keyTypes, final Principal[] issuers,
                                          final SSLEngine engine) {
        return chooseFrom(keyTypes);
    }

    @Nullable
    @Override
    public String[] getServerAliases(final String keyType, final Principal[] issuers) {
        return isEc(keyType) ? new String[]{mAlias} : null;
    }

    @Nullable
    @Override
    public String chooseServerAlias(final String keyType, final Principal[] issuers,
                                    final Socket socket) {
        return isEc(keyType) ? mAlias : null;
    }

    @Nullable
    @Override
    public String chooseEngineServerAlias(final String keyType, final Principal[] issuers,
                                          final SSLEngine engine) {
        return isEc(keyType) ? mAlias : null;
    }

    @Nullable
    @Override
    public X509Certificate[] getCertificateChain(final String alias) {
        return mAlias.equals(alias) ? mChain.clone() : null;
    }

    @Nullable
    @Override
    public PrivateKey getPrivateKey(final String alias) {
        return mAlias.equals(alias) ? mPrivateKey : null;
    }

    @Nullable
    private String chooseFrom(@Nullable final String[] keyTypes) {
        if (keyTypes == null) {
            return null;
        }
        for (final String keyType : keyTypes) {
            if (isEc(keyType)) {
                return mAlias;
            }
        }
        return null;
    }

    private static boolean isEc(@Nullable final String keyType) {
        if (keyType == null || keyType.isEmpty()) {
            return false;
        }
        final String normalized = keyType.toUpperCase(Locale.ROOT);
        return normalized.equals("EC") || normalized.startsWith("EC_");
    }
}
