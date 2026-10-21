package com.obby.android.localscreenshare.identity;

import org.junit.Test;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStoreException;
import java.security.UnrecoverableKeyException;

import static org.junit.Assert.assertEquals;

/**
 * Classification of a failed keystore access.
 *
 * <p>The device test of 2026-10-21 turned an interrupted keystore read into a deleted identity key,
 * because every failure looked equally conclusive. These cases pin the opposite rule: only a failure
 * that names the entry itself as permanently gone may replace it, and everything else — including
 * every failure whose reason is unknown — keeps the key.</p>
 */
public class KeyEntryFailurePolicyTest {

    @Test
    public void anInterruptionIsTransient() {
        assertEquals(KeyEntryFailurePolicy.Verdict.TRANSIENT,
            KeyEntryFailurePolicy.classify(new InterruptedException("cancelled scan")));
    }

    @Test
    public void anInterruptionWrappedByTheProviderIsTransient() {
        // The keystore wraps the interruption of the calling thread in a provider exception; the
        // cause is what identifies it, not the type the adapter catches.
        assertEquals(KeyEntryFailurePolicy.Verdict.TRANSIENT,
            KeyEntryFailurePolicy.classify(new KeyStoreException(
                new ProviderInterruption(new InterruptedException("read was interrupted")))));
    }

    @Test
    public void aPermanentlyInvalidatedKeyIsUnusable() {
        assertEquals(KeyEntryFailurePolicy.Verdict.UNUSABLE,
            KeyEntryFailurePolicy.classify(new KeyPermanentlyInvalidatedException()));
    }

    @Test
    public void aPermanentlyInvalidatedKeyWrappedByTheProviderIsUnusable() {
        assertEquals(KeyEntryFailurePolicy.Verdict.UNUSABLE,
            KeyEntryFailurePolicy.classify(new ProviderInterruption(
                new KeyPermanentlyInvalidatedException())));
    }

    @Test
    public void anUnrecoverableKeyIsUnusable() {
        assertEquals(KeyEntryFailurePolicy.Verdict.UNUSABLE,
            KeyEntryFailurePolicy.classify(new UnrecoverableKeyException("key blob is gone")));
    }

    @Test
    public void anIncompleteEntryIsUnusable() {
        // No exception was raised, but the entry carries no certificate or no private key.
        assertEquals(KeyEntryFailurePolicy.Verdict.UNUSABLE,
            KeyEntryFailurePolicy.classify(null));
    }

    @Test
    public void anUnavailableKeystoreIsTransient() {
        assertEquals(KeyEntryFailurePolicy.Verdict.TRANSIENT,
            KeyEntryFailurePolicy.classify(new IOException("keystore is not ready")));
    }

    @Test
    public void anUnknownFailureKeepsTheKey() {
        // Keeping a key costs one failed handshake; deleting one costs the identity and every pin a
        // peer stored for it.
        assertEquals(KeyEntryFailurePolicy.Verdict.TRANSIENT,
            KeyEntryFailurePolicy.classify(new ProviderFailure("opaque provider error")));
    }

    @Test
    public void describeNamesTheReason() {
        assertEquals("operation interrupted",
            KeyEntryFailurePolicy.describe(new InterruptedException()));
        assertEquals("key permanently invalidated",
            KeyEntryFailurePolicy.describe(new KeyPermanentlyInvalidatedException()));
        assertEquals("key unrecoverable",
            KeyEntryFailurePolicy.describe(new UnrecoverableKeyException()));
        assertEquals("keystore unavailable", KeyEntryFailurePolicy.describe(new IOException()));
        assertEquals("entry is incomplete", KeyEntryFailurePolicy.describe(null));
        assertEquals("ProviderFailure",
            KeyEntryFailurePolicy.describe(new ProviderFailure("opaque provider error")));
    }

    /**
     * Stands in for {@code android.security.keystore.KeyPermanentlyInvalidatedException}, which the
     * JVM cannot load; the policy matches it by simple name for exactly this reason.
     */
    private static final class KeyPermanentlyInvalidatedException
        extends GeneralSecurityException {

        private static final long serialVersionUID = 1L;
    }

    /**
     * Stands in for an exception the keystore provider raised without saying anything useful.
     */
    private static final class ProviderFailure extends GeneralSecurityException {

        private static final long serialVersionUID = 1L;

        ProviderFailure(final String message) {
            super(message);
        }
    }

    /**
     * Stands in for a provider exception that wraps the real reason.
     */
    private static final class ProviderInterruption extends GeneralSecurityException {

        private static final long serialVersionUID = 1L;

        ProviderInterruption(final Throwable cause) {
            super(cause);
        }
    }
}
