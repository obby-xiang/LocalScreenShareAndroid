package com.obby.android.localscreenshare.trust;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.identity.SpkiId;

/**
 * Decides whether a peer certificate may be used, in three fixed steps.
 *
 * <ol>
 *   <li><b>Self-consistency.</b> The id the caller expects (from discovery) must equal the id
 *   derived from the certificate. This step needs no storage, so it also protects the very first
 *   connection: an attacker can announce any id but cannot produce the matching private key.</li>
 *   <li><b>Pin lookup.</b> No pin means this is the first sighting.</li>
 *   <li><b>Fingerprint comparison.</b> An existing pin must match exactly; only surrounding
 *   whitespace is tolerated. No case folding, no partial matching.</li>
 * </ol>
 *
 * <p>The class is a pure function over bytes and strings: no I/O, no Android type. A mismatch on
 * step 1 cannot be resolved by any user action and must be reported as a security event, not as a
 * device update.</p>
 */
public final class PinPolicy {

    /**
     * Result of one decision.
     */
    public enum Outcome {
        /**
         * The certificate matches the stored pin.
         */
        MATCH,
        /**
         * The device was never pinned; this is a trust-on-first-use decision.
         */
        UNKNOWN,
        /**
         * The stored pin has a different fingerprint, or belongs to another id.
         */
        CHANGED,
        /**
         * The certificate does not correspond to the id the caller expects.
         */
        IDENTITY_MISMATCH
    }

    private PinPolicy() {
    }

    /**
     * Applies the decision to a certificate SPKI and the pin stored for its id.
     *
     * @param expectedId id selected in discovery, or null when no expectation exists
     * @param certSpki   X.509 {@code SubjectPublicKeyInfo} of the peer certificate
     * @param pin        pin of the certificate's id, or null when the device is new
     * @throws IllegalArgumentException if {@code certSpki} is null or empty
     */
    @NonNull
    public static Outcome decide(@Nullable final String expectedId,
                                 @NonNull final byte[] certSpki,
                                 @Nullable final TlsPin pin) {
        if (certSpki == null || certSpki.length == 0) {
            throw new IllegalArgumentException("certSpki must not be empty");
        }

        return decide(expectedId, SpkiId.deviceId(certSpki),
            SpkiId.fingerprint(certSpki), pin);
    }

    /**
     * Applies the decision to identities that were already derived, as the viewer does with the
     * values carried by discovery.
     *
     * @param expectedId      id selected in discovery, or null when no expectation exists
     * @param observedId      id derived from the certificate, or null when the peer presented none
     * @param observedFingerprint fingerprint derived from the certificate, or null when absent
     * @param pin             pin of the observed id, or null when the device is new
     */
    @NonNull
    public static Outcome decide(@Nullable final String expectedId,
                                 @Nullable final String observedId,
                                 @Nullable final String observedFingerprint,
                                 @Nullable final TlsPin pin) {
        final String normalizedObservedId = trimToNull(observedId);
        if (expectedId != null && !expectedId.trim().isEmpty()
            && !expectedId.trim().equals(normalizedObservedId)) {
            return Outcome.IDENTITY_MISMATCH;
        }
        if (pin == null) {
            return Outcome.UNKNOWN;
        }
        if (normalizedObservedId == null || !pin.getId().equals(normalizedObservedId)) {
            return Outcome.CHANGED;
        }
        final String normalizedFingerprint = trimToNull(observedFingerprint);
        return normalizedFingerprint != null
            && pin.getFingerprint().trim().equals(normalizedFingerprint)
            ? Outcome.MATCH : Outcome.CHANGED;
    }

    @Nullable
    private static String trimToNull(@Nullable final String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
