package com.obby.android.localscreenshare.identity.tls;

import org.junit.Before;
import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class IdentityKeyAdapterTest {

    private static final String ALIAS = "lss-identity";

    private KeyPair mKeyPair;

    private X509Certificate mCertificate;

    private IdentityKeyAdapter mAdapter;

    @Before
    public void setUp() throws Exception {
        mKeyPair = generateKeyPair();
        mCertificate = TlsCertificate.fromEncoded(
            SelfSignedCertificateEncoder.encode(mKeyPair.getPublic(), mKeyPair.getPrivate()))
            .getCertificate();
        mAdapter = new IdentityKeyAdapter(ALIAS, mCertificate, mKeyPair.getPrivate());
    }

    @Test
    public void answersTheServerAliasForEcKeyTypes() {
        assertEquals(ALIAS, mAdapter.chooseServerAlias("EC", null, null));
        assertEquals(ALIAS, mAdapter.chooseEngineServerAlias("EC_RSA", null, null));
        assertNull(mAdapter.chooseServerAlias("RSA", null, null));
        assertArrayEquals(new String[]{ALIAS}, mAdapter.getServerAliases("EC", null));
        assertNull(mAdapter.getServerAliases("RSA", null));
    }

    @Test
    public void answersTheClientAliasForEcKeyTypes() {
        assertEquals(ALIAS, mAdapter.chooseClientAlias(new String[]{"RSA", "EC"}, null, null));
        assertEquals(ALIAS,
            mAdapter.chooseEngineClientAlias(new String[]{"EC_EC"}, null, null));
        assertNull(mAdapter.chooseClientAlias(new String[]{"RSA"}, null, null));
        assertNull(mAdapter.chooseClientAlias(null, null, null));
        assertArrayEquals(new String[]{ALIAS}, mAdapter.getClientAliases("EC", null));
    }

    @Test
    public void returnsTheSingleCertificateChain() {
        final X509Certificate[] chain = mAdapter.getCertificateChain(ALIAS);

        assertEquals(1, chain.length);
        assertSame(mCertificate, chain[0]);
    }

    @Test
    public void returnsTheSamePrivateKeyHandle() {
        assertSame(mKeyPair.getPrivate(), mAdapter.getPrivateKey(ALIAS));
    }

    @Test
    public void rejectsAnotherAlias() {
        assertNull(mAdapter.getCertificateChain("another"));
        assertNull(mAdapter.getPrivateKey("another"));
    }

    private static KeyPair generateKeyPair() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }
}
