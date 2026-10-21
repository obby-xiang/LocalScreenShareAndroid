package com.obby.android.localscreenshare.identity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loads the identity key entry and replaces it only when the entry itself is unusable.
 *
 * <p><b>Why replacement is guarded.</b> The identity is the key: peers pin its fingerprint, the
 * announcement and the handshake certificate name it, and favorites are bound to it. Deleting the
 * entry regenerates it and invalidates all of that at once, which is why the device test saw two
 * devices that could see each other but refused every connection (N3). Replacement is therefore
 * allowed only for a failure {@link KeyEntryFailurePolicy} calls {@link
 * KeyEntryFailurePolicy.Verdict#UNUSABLE}, only after a second read confirms the first one, only
 * once per process, and it is reported with the reason and both fingerprints so the event can be
 * logged at error level and followed by an identity-change event.</p>
 *
 * <p><b>Interruption.</b> An interrupted thread does not touch the keystore at all: a cancelled
 * scan interrupts probe threads, and the keystore answers such a read with an exception that says
 * nothing about the entry.</p>
 *
 * <p>Pure JVM through {@link EntryStore}: the Android adapter supplies the AndroidKeyStore, the
 * tests supply a software store that can fail on demand and counts deletions.</p>
 */
public final class IdentityKeyEntryLoader {

    /**
     * Read, delete and create operations of the identity entry.
     *
     * <p>{@link #read()} returns null for an entry that carries no usable key material — no alias,
     * no certificate, no private key — and throws only when the keystore itself failed.</p>
     */
    public interface EntryStore {

        /**
         * Returns the stored key pair, or null when the entry is missing or incomplete.
         *
         * @throws GeneralSecurityException if the keystore refused the read
         * @throws IOException if the keystore could not be opened
         */
        @Nullable
        KeyPair read() throws GeneralSecurityException, IOException;

        /**
         * Deletes the entry; a missing entry is not an error.
         *
         * @throws GeneralSecurityException if the keystore refused the deletion
         * @throws IOException if the keystore could not be opened
         */
        void delete() throws GeneralSecurityException, IOException;

        /**
         * Generates and stores a new key pair.
         *
         * @throws GeneralSecurityException if no key can be created
         * @throws IOException if the keystore could not be opened
         */
        @NonNull
        KeyPair create() throws GeneralSecurityException, IOException;
    }

    /**
     * Answers whether a stored key can take part in a TLS handshake.
     */
    @FunctionalInterface
    public interface UsabilityProbe {

        /**
         * Returns whether {@code keyPair} is usable; an inconclusive answer must return true so an
         * unprovable key is kept.
         */
        boolean isUsable(@NonNull KeyPair keyPair);
    }

    /**
     * What {@link #loadOrCreate()} did to the entry.
     */
    public enum Outcome {
        /**
         * The stored key was read and kept.
         */
        REUSED,

        /**
         * No entry existed; a key was generated.
         */
        CREATED,

        /**
         * An existing entry was deleted and replaced.
         */
        REPLACED
    }

    /**
     * Result of one load.
     */
    public static final class LoadResult {

        @NonNull
        private final KeyPair mKeyPair;

        @NonNull
        private final Outcome mOutcome;

        @Nullable
        private final String mPreviousFingerprint;

        @Nullable
        private final String mReplacementReason;

        private LoadResult(@NonNull final KeyPair keyPair, @NonNull final Outcome outcome,
            @Nullable final String previousFingerprint,
            @Nullable final String replacementReason) {
            mKeyPair = keyPair;
            mOutcome = outcome;
            mPreviousFingerprint = previousFingerprint;
            mReplacementReason = replacementReason;
        }

        @NonNull
        public KeyPair getKeyPair() {
            return mKeyPair;
        }

        @NonNull
        public Outcome getOutcome() {
            return mOutcome;
        }

        /**
         * Fingerprint of the key that was replaced, or null when nothing was replaced.
         */
        @Nullable
        public String getPreviousFingerprint() {
            return mPreviousFingerprint;
        }

        /**
         * Why the entry was replaced, or null when nothing was replaced.
         */
        @Nullable
        public String getReplacementReason() {
            return mReplacementReason;
        }

        /**
         * True when an existing entry was deleted, which is a security event.
         */
        public boolean isReplaced() {
            return mOutcome == Outcome.REPLACED;
        }
    }

    /**
     * Thrown when no usable key could be produced.
     *
     * <p>The verdict tells the caller whether the entry still exists and may work later
     * ({@link KeyEntryFailurePolicy.Verdict#TRANSIENT}) or is the reason for the failure.</p>
     */
    public static final class KeyEntryUnavailableException extends GeneralSecurityException {

        private static final long serialVersionUID = 1L;

        @NonNull
        private final KeyEntryFailurePolicy.Verdict mVerdict;

        KeyEntryUnavailableException(@NonNull final String message, @Nullable final Throwable cause,
            @NonNull final KeyEntryFailurePolicy.Verdict verdict) {
            super(message, cause);
            mVerdict = verdict;
        }

        @NonNull
        public KeyEntryFailurePolicy.Verdict getVerdict() {
            return mVerdict;
        }
    }

    @NonNull
    private final EntryStore mStore;

    @NonNull
    private final UsabilityProbe mProbe;

    // A silently replaced key changes the identity of this device, so it happens at most once per
    // process: a second occurrence within one run means the keystore is broken, not the entry.
    @NonNull
    private final AtomicBoolean mEntryReplaced = new AtomicBoolean();

    /**
     * Creates a loader.
     *
     * @param store storage of the entry
     * @param probe usability probe applied to the stored key
     */
    public IdentityKeyEntryLoader(@NonNull final EntryStore store,
        @NonNull final UsabilityProbe probe) {
        mStore = Objects.requireNonNull(store, "store");
        mProbe = Objects.requireNonNull(probe, "probe");
    }

    /**
     * Returns the stored key pair, creating one only when the entry is missing or unusable.
     *
     * @throws KeyEntryUnavailableException if no usable key could be produced; the stored entry is
     *     untouched when the verdict is {@link KeyEntryFailurePolicy.Verdict#TRANSIENT}
     */
    @NonNull
    public LoadResult loadOrCreate() throws KeyEntryUnavailableException {
        requireNotInterrupted("before reading the identity entry");

        KeyPair existing = null;
        Throwable readFailure = null;
        try {
            existing = mStore.read();
        } catch (GeneralSecurityException | IOException e) {
            if (KeyEntryFailurePolicy.classify(e) == KeyEntryFailurePolicy.Verdict.TRANSIENT) {
                throw rejected("the identity entry could not be read", e);
            }
            // Conclusive: the entry itself cannot be read any more — permanently invalidated, or a
            // key blob without its key. It is still confirmed once before anything is deleted.
            readFailure = e;
        }

        if (existing == null && readFailure == null) {
            return create(Outcome.CREATED, null, null);
        }
        if (existing != null && mProbe.isUsable(existing)) {
            return new LoadResult(existing, Outcome.REUSED, null, null);
        }

        // A single unusable answer is not enough to delete a key. Read the entry again: an
        // operation that failed for its own reasons looks exactly like this, and a key that is in
        // fact usable must never be replaced.
        requireNotInterrupted("before confirming the identity entry");
        final String previousFingerprint = existing == null ? null : fingerprintOf(existing);
        KeyPair confirmed = null;
        try {
            confirmed = mStore.read();
        } catch (GeneralSecurityException | IOException e) {
            if (KeyEntryFailurePolicy.classify(e) == KeyEntryFailurePolicy.Verdict.TRANSIENT) {
                throw rejected("the identity entry could not be confirmed", e);
            }
            readFailure = e;
        }
        if (confirmed != null && mProbe.isUsable(confirmed)) {
            return new LoadResult(confirmed, Outcome.REUSED, null, null);
        }

        if (!mEntryReplaced.compareAndSet(false, true)) {
            throw new KeyEntryUnavailableException(
                "the identity entry was already replaced once in this process", null,
                KeyEntryFailurePolicy.Verdict.UNUSABLE);
        }
        requireNotInterrupted("before replacing the identity entry");

        final String reason = confirmed == null
            ? KeyEntryFailurePolicy.describe(readFailure) : "key refused the usability probe";
        try {
            mStore.delete();
        } catch (GeneralSecurityException | IOException e) {
            throw rejected("the unusable identity entry could not be deleted", e);
        }
        return create(Outcome.REPLACED, previousFingerprint, reason);
    }

    @NonNull
    private LoadResult create(@NonNull final Outcome outcome,
        @Nullable final String previousFingerprint, @Nullable final String reason)
        throws KeyEntryUnavailableException {
        final KeyPair generated;
        try {
            generated = mStore.create();
        } catch (GeneralSecurityException | IOException e) {
            throw rejected("the identity key could not be created", e);
        }
        return new LoadResult(generated, outcome, previousFingerprint, reason);
    }

    // Converts a keystore failure into the loader's failure; the verdict comes from the policy so a
    // transient failure never reaches the deletion path.
    @NonNull
    private KeyEntryUnavailableException rejected(@NonNull final String message,
        @NonNull final Throwable cause) {
        return new KeyEntryUnavailableException(message + ": "
            + KeyEntryFailurePolicy.describe(cause), cause, KeyEntryFailurePolicy.classify(cause));
    }

    private void requireNotInterrupted(@NonNull final String when)
        throws KeyEntryUnavailableException {
        if (Thread.currentThread().isInterrupted()) {
            throw new KeyEntryUnavailableException("the calling thread was interrupted " + when,
                new InterruptedException("thread was interrupted"), KeyEntryFailurePolicy.Verdict.TRANSIENT);
        }
    }

    @NonNull
    private static String fingerprintOf(@NonNull final KeyPair keyPair) {
        final byte[] spki = keyPair.getPublic() == null ? null : keyPair.getPublic().getEncoded();
        return spki == null || spki.length == 0 ? "" : SpkiId.fingerprint(spki);
    }
}
