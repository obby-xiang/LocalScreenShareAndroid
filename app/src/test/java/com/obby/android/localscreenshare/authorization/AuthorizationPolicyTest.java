package com.obby.android.localscreenshare.authorization;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link AuthorizationPolicy}.
 */
public class AuthorizationPolicyTest {

    private static final String DEVICE_ID = "AAAAAAAAAAAAAAAAAAAAAAAAAA";

    private static final String FINGERPRINT = "ZmFrZS1maW5nZXJwcmludC1mYWtlLWZpbmdlcnByaW50";

    @Test
    public void aDisabledFeatureAllowsEveryone() {
        assertEquals(AuthorizationPolicy.Verdict.ALLOW,
            AuthorizationPolicy.decide(false, false));
    }

    @Test
    public void anAuthorizedIdentityIsAllowed() {
        assertEquals(AuthorizationPolicy.Verdict.ALLOW,
            AuthorizationPolicy.decide(true, true));
    }

    @Test
    public void anUnknownIdentityNeedsApproval() {
        assertEquals(AuthorizationPolicy.Verdict.REQUIRE_APPROVAL,
            AuthorizationPolicy.decide(true, false));
    }

    @Test
    public void aGrantMatchesOnlyWithTheSameIdAndFingerprint() {
        assertTrue(AuthorizationPolicy.isGrantValid(DEVICE_ID, FINGERPRINT, DEVICE_ID,
            FINGERPRINT));
        assertFalse(AuthorizationPolicy.isGrantValid(DEVICE_ID, FINGERPRINT, "other",
            FINGERPRINT));
        assertFalse(AuthorizationPolicy.isGrantValid(DEVICE_ID, FINGERPRINT, DEVICE_ID,
            "other"));
    }

    @Test
    public void aGrantComparisonOnlyTrimsTheEnds() {
        assertTrue(AuthorizationPolicy.isGrantValid(" " + DEVICE_ID + " ",
            "\t" + FINGERPRINT + "\n", DEVICE_ID, FINGERPRINT));
        assertFalse(AuthorizationPolicy.isGrantValid(DEVICE_ID, FINGERPRINT,
            DEVICE_ID.toLowerCase(java.util.Locale.ROOT), FINGERPRINT));
    }

    @Test
    public void aMissingGrantOrIdentityNeverMatches() {
        assertFalse(AuthorizationPolicy.isGrantValid(null, FINGERPRINT, DEVICE_ID,
            FINGERPRINT));
        assertFalse(AuthorizationPolicy.isGrantValid(DEVICE_ID, null, DEVICE_ID, FINGERPRINT));
        assertFalse(AuthorizationPolicy.isGrantValid(DEVICE_ID, FINGERPRINT, null, null));
        assertFalse(AuthorizationPolicy.isGrantValid(DEVICE_ID, FINGERPRINT, "", ""));
    }

    @Test
    public void onlyAlwaysAllowWithAVerifiedIdentityIsPersisted() {
        assertTrue(AuthorizationPolicy.shouldPersist(ApprovalOutcome.ALLOW_ALWAYS, true));
        assertFalse(AuthorizationPolicy.shouldPersist(ApprovalOutcome.ALLOW_ALWAYS, false));
        assertFalse(AuthorizationPolicy.shouldPersist(ApprovalOutcome.ALLOW_ONCE, true));
        assertFalse(AuthorizationPolicy.shouldPersist(ApprovalOutcome.DENY, true));
        assertFalse(AuthorizationPolicy.shouldPersist(ApprovalOutcome.TIMEOUT, true));
    }

    @Test
    public void theRejectionReasonsAreStableWireValues() {
        assertEquals("authorization_denied", AuthorizationPolicy.REASON_DENIED);
        assertEquals("authorization_timeout", AuthorizationPolicy.REASON_TIMEOUT);
        assertEquals("authorization_revoked", AuthorizationPolicy.REASON_REVOKED);
    }
}
