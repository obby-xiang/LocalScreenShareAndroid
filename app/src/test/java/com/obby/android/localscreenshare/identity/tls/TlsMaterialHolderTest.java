package com.obby.android.localscreenshare.identity.tls;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.identity.IdentityUnavailableException;
import com.obby.android.localscreenshare.identity.SpkiId;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Material cache and identity-change events of the TLS holder.
 *
 * <p>The holder is the only identity a running session may announce: the 2026-10-21 device test
 * showed what happens when the announcement, the {@code ServerInfo} and the handshake certificate
 * are three independent snapshots (N3). These cases cover the two properties that keep them one
 * value — the material follows a key that changed, and a change is reported exactly once — plus the
 * two ways it must not act: an interrupted caller and a failing keystore.</p>
 */
public class TlsMaterialHolderTest {

    @After
    public void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    public void theMaterialIsCachedForOneKey() throws Exception {
        final KeySource source = new KeySource(newKeyPair());
        final TlsMaterialHolder holder = new TlsMaterialHolder(source);

        final TlsMaterial first = holder.material();
        final TlsMaterial second = holder.material();

        assertSame(first, second);
        assertSame(first, holder.getCachedMaterial());
        assertEquals(2, source.callCount());
    }

    @Test
    public void aReplacedKeyProducesNewMaterialAndOneEvent() throws Exception {
        final KeySource source = new KeySource(newKeyPair());
        final TlsMaterialHolder holder = new TlsMaterialHolder(source);
        final List<String> events = new ArrayList<>();
        holder.addIdentityChangeListener((previousFingerprint, material) ->
            events.add(previousFingerprint + "->" + material.getFingerprint()));

        final TlsMaterial before = holder.material();
        final KeyPair replaced = newKeyPair();
        source.replaceKeyPair(replaced);
        final TlsMaterial after = holder.material();

        assertNotEquals(before.getFingerprint(), after.getFingerprint());
        assertEquals(fingerprintOf(replaced), after.getFingerprint());
        assertEquals(fingerprintOf(replaced), after.getCertificate().getFingerprint());
        assertEquals(SpkiId.deviceId(replaced.getPublic().getEncoded()), after.getDeviceId());
        assertEquals(List.of(before.getFingerprint() + "->" + after.getFingerprint()), events);
    }

    @Test
    public void anUnchangedKeyProducesNoEvent() throws Exception {
        final KeySource source = new KeySource(newKeyPair());
        final TlsMaterialHolder holder = new TlsMaterialHolder(source);
        final List<String> events = new ArrayList<>();
        holder.addIdentityChangeListener((previousFingerprint, material) -> events.add("changed"));

        holder.material();
        holder.material();

        assertTrue(events.isEmpty());
    }

    @Test
    public void aRemovedListenerIsNotNotified() throws Exception {
        final KeySource source = new KeySource(newKeyPair());
        final TlsMaterialHolder holder = new TlsMaterialHolder(source);
        final List<String> events = new ArrayList<>();
        final TlsMaterialHolder.IdentityChangeListener listener =
            (previousFingerprint, material) -> events.add("changed");
        holder.addIdentityChangeListener(listener);
        holder.material();
        holder.removeIdentityChangeListener(listener);

        source.replaceKeyPair(newKeyPair());
        holder.material();

        assertTrue(events.isEmpty());
    }

    @Test
    public void aFailingKeySourceReportsUnavailableAndKeepsTheCache() throws Exception {
        final KeySource source = new KeySource(newKeyPair());
        final TlsMaterialHolder holder = new TlsMaterialHolder(source);
        final TlsMaterial cached = holder.material();
        final List<String> events = new ArrayList<>();
        holder.addIdentityChangeListener((previousFingerprint, material) -> events.add("changed"));

        source.fail();
        try {
            holder.material();
            fail("a keystore that cannot provide a key must be reported");
        } catch (IdentityUnavailableException e) {
            // Expected: the caller learns that the material is unavailable.
        }

        assertSame(cached, holder.getCachedMaterial());
        assertTrue(events.isEmpty());
    }

    @Test
    public void anInterruptedCallerDoesNotReadTheKey() throws Exception {
        final KeySource source = new KeySource(newKeyPair());
        final TlsMaterialHolder holder = new TlsMaterialHolder(source);
        Thread.currentThread().interrupt();

        try {
            holder.material();
            fail("an interrupted caller must not touch the keystore");
        } catch (IdentityUnavailableException e) {
            // Expected: nothing was read, so nothing can have been signed.
        }

        assertEquals(0, source.callCount());
        assertNull(holder.getCachedMaterial());
    }

    @Test
    public void theCacheIsEmptyUntilTheFirstLoad() {
        final TlsMaterialHolder holder = new TlsMaterialHolder(new KeySource(newKeyPair()));

        assertNull(holder.getCachedMaterial());
    }

    @NonNull
    private static String fingerprintOf(@NonNull final KeyPair keyPair) {
        return SpkiId.fingerprint(keyPair.getPublic().getEncoded());
    }

    @NonNull
    private static KeyPair newKeyPair() {
        try {
            final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("EC is unavailable", e);
        }
    }

    /**
     * Key source with a replaceable key pair and a failure switch; counts calls so a test can assert
     * that an interrupted caller never reached the keystore.
     */
    private static final class KeySource implements TlsMaterialHolder.KeyPairSource {

        @NonNull
        private KeyPair mKeyPair;

        private boolean mFails;

        private int mCallCount;

        KeySource(@NonNull final KeyPair keyPair) {
            mKeyPair = keyPair;
        }

        void replaceKeyPair(@NonNull final KeyPair keyPair) {
            mKeyPair = keyPair;
        }

        void fail() {
            mFails = true;
        }

        int callCount() {
            return mCallCount;
        }

        @NonNull
        @Override
        public KeyPair loadOrCreate() throws GeneralSecurityException, IOException {
            mCallCount++;
            if (mFails) {
                throw new IOException("keystore is not ready");
            }
            return mKeyPair;
        }
    }
}
