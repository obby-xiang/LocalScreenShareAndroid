package com.obby.android.localscreenshare.authorization;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Pure decisions of the viewing authorization: who may watch, and what an answer means.
 *
 * <p>Trust on first use answers "is this the same machine"; this policy answers "may that machine
 * watch". They are deliberately separate: an identity may be pinned and still be waiting for
 * approval, and revoking an authorization never touches the pin.</p>
 *
 * <p>The defaults are the safe ones. When the feature is enabled an identity that is not in the
 * durable set is asked about; when it is disabled the gate short-circuits and the behaviour is the
 * one before this feature existed. Nothing here reads storage or touches Android.</p>
 */
public final class AuthorizationPolicy {

    /**
     * How long a waiting peer is kept before its request is refused.
     *
     * <p>Sixty seconds is a product choice, not a security parameter: it only decides how long the
     * viewer waits for an answer. The refusal itself happens either way.</p>
     */
    public static final long APPROVAL_TIMEOUT_MS = 60_000L;

    /**
     * Rejection reason reported when the server user refused the request.
     */
    public static final String REASON_DENIED = "authorization_denied";

    /**
     * Rejection reason reported when nobody answered within {@link #APPROVAL_TIMEOUT_MS}.
     */
    public static final String REASON_TIMEOUT = "authorization_timeout";

    /**
     * Rejection reason reported when a durable authorization was revoked while the peer watched.
     */
    public static final String REASON_REVOKED = "authorization_revoked";

    /**
     * What the gate should do with one arrival.
     */
    public enum Verdict {
        /**
         * Let the peer watch without asking.
         */
        ALLOW,

        /**
         * Hold the peer until the server user answers.
         */
        REQUIRE_APPROVAL
    }

    private AuthorizationPolicy() {
    }

    /**
     * Decides whether one arrival needs an approval.
     *
     * @param isEnabled           whether the authorization feature is on
     * @param isIdentityAuthorized whether the verified identity is in the durable set
     */
    @NonNull
    public static Verdict decide(final boolean isEnabled, final boolean isIdentityAuthorized) {
        if (!isEnabled || isIdentityAuthorized) {
            return Verdict.ALLOW;
        }
        return Verdict.REQUIRE_APPROVAL;
    }

    /**
     * Returns whether a stored grant matches the identity being checked.
     *
     * <p>Both values must be equal after trimming the ends only: no case folding, no prefix match.
     * A grant for the same id with another fingerprint belongs to a different identity, because the
     * id is derived from the very key the fingerprint describes.</p>
     *
     * @param grantedId           device id of the stored grant, or null when there is no grant
     * @param grantedFingerprint  fingerprint of the stored grant, or null when there is no grant
     * @param deviceId            device id being checked, or null when the call has none
     * @param fingerprint         fingerprint being checked, or null when the call has none
     */
    public static boolean isGrantValid(@Nullable final String grantedId,
                                       @Nullable final String grantedFingerprint,
                                       @Nullable final String deviceId,
                                       @Nullable final String fingerprint) {
        if (grantedId == null || grantedFingerprint == null
            || deviceId == null || fingerprint == null) {
            return false;
        }
        return grantedId.trim().equals(deviceId.trim())
            && grantedFingerprint.trim().equals(fingerprint.trim());
    }

    /**
     * Returns whether an answer should be written to the durable set.
     *
     * <p>Only "always allow" persists, and only for a verified identity: remembering a peer that
     * presented no certificate would authorize an address that any device can take over.</p>
     */
    public static boolean shouldPersist(@NonNull final ApprovalOutcome outcome,
                                        final boolean hasVerifiedIdentity) {
        return outcome == ApprovalOutcome.ALLOW_ALWAYS && hasVerifiedIdentity;
    }
}
