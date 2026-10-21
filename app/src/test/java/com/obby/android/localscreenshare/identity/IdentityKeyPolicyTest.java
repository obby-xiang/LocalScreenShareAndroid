package com.obby.android.localscreenshare.identity;

import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Authorization set and migration decision of the identity key.
 *
 * <p>The device path (AndroidKeyStore) is not reachable from the JVM, but both halves of the
 * decision are: which digests a key is generated with, and what a raw-digest signature attempt says
 * about an existing key. The probe runs against real software EC keys here, which is the same JCA
 * call Conscrypt makes during a handshake, and against a key that refuses the operation, which is
 * how an AndroidKeyStore key without the {@code NONE} authorization behaves.</p>
 */
public class IdentityKeyPolicyTest {

    @Test
    public void newKeysAreAuthorizedForSha256AndRawDigests() {
        assertTrue(IdentityKeyPolicy.DIGESTS.contains(IdentityKeyPolicy.DIGEST_SHA256));
        assertTrue(IdentityKeyPolicy.DIGESTS.contains(IdentityKeyPolicy.DIGEST_NONE));
        assertEquals(2, IdentityKeyPolicy.DIGESTS.size());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void theAuthorizationSetCannotBeWidenedAtRuntime() {
        IdentityKeyPolicy.DIGESTS.add("MD5");
    }

    @Test
    public void aSoftwareEcKeySignsARawDigest() throws Exception {
        assertEquals(IdentityKeyPolicy.RawDigestSupport.SUPPORTED,
            IdentityKeyPolicy.probeRawDigestSupport(keyPair().getPrivate()));
    }

    @Test
    public void aKeyThatRefusesTheOperationMustBeReplaced() {
        final IdentityKeyPolicy.RawDigestSupport support =
            IdentityKeyPolicy.probeRawDigestSupport(new UnsupportedKey());

        assertEquals(IdentityKeyPolicy.RawDigestSupport.UNSUPPORTED, support);
        assertTrue(IdentityKeyPolicy.mustReplace(support));
    }

    @Test
    public void aSupportedKeyIsKept() {
        assertFalse(IdentityKeyPolicy.mustReplace(IdentityKeyPolicy.RawDigestSupport.SUPPORTED));
    }

    @Test
    public void anInconclusiveProbeKeepsTheKey() {
        // Replacing on an inconclusive answer would change the identity on every load.
        assertFalse(IdentityKeyPolicy.mustReplace(IdentityKeyPolicy.RawDigestSupport.UNKNOWN));
    }

    private static KeyPair keyPair() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    /**
     * Stands in for a keystore key whose authorization set excludes the raw digest: it presents
     * itself as an EC key and rejects any signature initialization, exactly as the device keystore
     * rejects {@code NONEwithECDSA} with {@code INCOMPATIBLE_DIGEST}.
     */
    private static final class UnsupportedKey implements PrivateKey {

        @Override
        public String getAlgorithm() {
            return "EC";
        }

        @Override
        public String getFormat() {
            return null;
        }

        @Override
        public byte[] getEncoded() {
            return null;
        }
    }
}
