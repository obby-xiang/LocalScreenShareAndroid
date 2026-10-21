package com.obby.android.localscreenshare.authorization;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;


import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.authorization.store.AuthorizationStore;
import com.obby.android.localscreenshare.authorization.store.support.FakeAuthorizationStorage;
import com.obby.android.localscreenshare.core.support.MutableClock;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link AuthorizationGate}.
 */
public class AuthorizationGateTest {

    private static final String DEVICE_ID = "AAAAAAAAAAAAAAAAAAAAAAAAAA";

    private static final String FINGERPRINT = "ZmFrZS1maW5nZXJwcmludC1mYWtlLWZpbmdlcnByaW50";

    private static final long TIMEOUT_MS = 60_000L;

    @Test
    public void aDisabledFeatureAllowsWithoutARequest() {
        final Fixture fixture = new Fixture(false);

        assertTrue(fixture.gate.isAuthorized(DEVICE_ID, FINGERPRINT));
        assertTrue(fixture.gate.pendingRequests().isEmpty());
    }

    @Test
    public void anUnknownIdentityIsParkedAndPromptsTheUser() {
        final Fixture fixture = new Fixture(true);

        final long requestId = fixture.gate.request(DEVICE_ID, FINGERPRINT, "Laptop");

        assertEquals(1, fixture.listener.requested.size());
        assertEquals("Laptop", fixture.listener.requested.get(0).getDeviceName());
        assertEquals(AuthorizationGate.State.PENDING, fixture.gate.poll(requestId));
        assertEquals(1, fixture.gate.getPendingCount());
    }

    @Test
    public void allowOnceDoesNotWriteTheDurableSet() {
        final Fixture fixture = new Fixture(true);
        final long requestId = fixture.gate.request(DEVICE_ID, FINGERPRINT, "Laptop");

        assertTrue(fixture.gate.resolve(requestId, ApprovalOutcome.ALLOW_ONCE));

        assertEquals(AuthorizationGate.State.ALLOWED_ONCE, fixture.gate.poll(requestId));
        assertTrue(fixture.store.entries().isEmpty());
        assertFalse(fixture.gate.isAuthorized(DEVICE_ID, FINGERPRINT));
        assertEquals(1, fixture.listener.resolved.size());
    }

    @Test
    public void alwaysAllowWritesTheDurableSet() {
        final Fixture fixture = new Fixture(true);
        final long requestId = fixture.gate.request(DEVICE_ID, FINGERPRINT, "Laptop");

        fixture.gate.resolve(requestId, ApprovalOutcome.ALLOW_ALWAYS);

        assertEquals(AuthorizationGate.State.ALLOWED_ALWAYS, fixture.gate.poll(requestId));
        assertEquals(FINGERPRINT, fixture.store.find(DEVICE_ID).getFingerprint());
        assertTrue(fixture.gate.isAuthorized(DEVICE_ID, FINGERPRINT));
        assertFalse(fixture.gate.isAuthorized(DEVICE_ID, "another-fingerprint"));
    }

    @Test
    public void alwaysAllowWithoutAVerifiedIdentityDegradesToOnce() {
        final Fixture fixture = new Fixture(true);
        final long requestId = fixture.gate.request(null, null, null);

        fixture.gate.resolve(requestId, ApprovalOutcome.ALLOW_ALWAYS);

        assertEquals(AuthorizationGate.State.ALLOWED_ONCE, fixture.gate.poll(requestId));
        assertTrue(fixture.store.entries().isEmpty());
    }

    @Test
    public void denyIsReportedAndNothingIsWritten() {
        final Fixture fixture = new Fixture(true);
        final long requestId = fixture.gate.request(DEVICE_ID, FINGERPRINT, "Laptop");

        fixture.gate.resolve(requestId, ApprovalOutcome.DENY);

        assertEquals(AuthorizationGate.State.DENIED, fixture.gate.poll(requestId));
        assertTrue(fixture.store.entries().isEmpty());
        assertEquals(ApprovalOutcome.DENY, fixture.listener.resolved.get(0));
    }

    @Test
    public void theRequestTimesOutAndNotifiesTheListener() {
        final Fixture fixture = new Fixture(true);
        final long requestId = fixture.gate.request(DEVICE_ID, FINGERPRINT, "Laptop");

        fixture.clock.advance(TIMEOUT_MS - 1L);
        assertEquals(AuthorizationGate.State.PENDING, fixture.gate.poll(requestId));

        fixture.clock.advance(1L);
        assertEquals(AuthorizationGate.State.TIMED_OUT, fixture.gate.poll(requestId));
        assertEquals(1, fixture.listener.resolved.size());
        assertEquals(ApprovalOutcome.TIMEOUT, fixture.listener.resolved.get(0));
        assertTrue(fixture.store.entries().isEmpty());
    }

    @Test
    public void anUnknownOrSettledRequestIdNeverTurnsIntoAGrant() {
        final Fixture fixture = new Fixture(true);
        final long requestId = fixture.gate.request(DEVICE_ID, FINGERPRINT, "Laptop");
        fixture.gate.resolve(requestId, ApprovalOutcome.ALLOW_ONCE);

        assertEquals(AuthorizationGate.State.ALLOWED_ONCE, fixture.gate.poll(requestId));
        assertEquals(AuthorizationGate.State.TIMED_OUT, fixture.gate.poll(requestId));
        assertEquals(AuthorizationGate.State.TIMED_OUT, fixture.gate.poll(999L));
        assertFalse(fixture.gate.resolve(999L, ApprovalOutcome.ALLOW_ALWAYS));
    }

    @Test
    public void aSecondAnswerIsIgnored() {
        final Fixture fixture = new Fixture(true);
        final long requestId = fixture.gate.request(DEVICE_ID, FINGERPRINT, "Laptop");

        assertTrue(fixture.gate.resolve(requestId, ApprovalOutcome.DENY));
        assertFalse(fixture.gate.resolve(requestId, ApprovalOutcome.ALLOW_ALWAYS));

        assertEquals(AuthorizationGate.State.DENIED, fixture.gate.poll(requestId));
        assertTrue(fixture.store.entries().isEmpty());
    }

    @Test
    public void cancellingForgetsTheRequestAndWithdrawsThePrompt() {
        final Fixture fixture = new Fixture(true);
        final long requestId = fixture.gate.request(DEVICE_ID, FINGERPRINT, "Laptop");

        fixture.gate.cancel(requestId);

        assertEquals(1, fixture.listener.cancelled.size());
        assertEquals(0, fixture.gate.getPendingCount());
        assertEquals(AuthorizationGate.State.TIMED_OUT, fixture.gate.poll(requestId));
    }

    @Test
    public void switchingTheFeatureOffReleasesAWaitingPeer() {
        final Fixture fixture = new Fixture(true);
        final long requestId = fixture.gate.request(DEVICE_ID, FINGERPRINT, "Laptop");

        fixture.isEnabled = false;

        assertEquals(AuthorizationGate.State.ALLOWED_ONCE, fixture.gate.poll(requestId));
        assertTrue(fixture.store.entries().isEmpty());
        assertEquals(ApprovalOutcome.ALLOW_ONCE, fixture.listener.resolved.get(0));
    }

    @Test
    public void anAlreadyAuthorizedIdentityIsNotPrompted() {
        final Fixture fixture = new Fixture(true);
        fixture.store.authorize(DEVICE_ID, FINGERPRINT, "Laptop", 1L);

        final long requestId = fixture.gate.request(DEVICE_ID, FINGERPRINT, "Laptop");

        assertTrue(fixture.listener.requested.isEmpty());
        assertEquals(AuthorizationGate.State.ALLOWED_ALWAYS, fixture.gate.poll(requestId));
    }

    @Test
    public void revokingAGrantMakesTheIdentityUnauthorized() {
        final Fixture fixture = new Fixture(true);
        fixture.store.authorize(DEVICE_ID, FINGERPRINT, "Laptop", 1L);
        assertTrue(fixture.gate.isAuthorized(DEVICE_ID, FINGERPRINT));

        fixture.store.revoke(DEVICE_ID);

        assertFalse(fixture.gate.isAuthorized(DEVICE_ID, FINGERPRINT));
    }

    // Shared fixture: one store over fake storage, one mutable clock and one recording listener.
    private static final class Fixture {
        private final MutableClock clock = new MutableClock(1_000L);

        private final AuthorizationStore store =
            new AuthorizationStore(new FakeAuthorizationStorage());

        private final RecordingListener listener = new RecordingListener();

        private boolean isEnabled;

        private final AuthorizationGate gate;

        private Fixture(final boolean isEnabled) {
            this.isEnabled = isEnabled;
            gate = new AuthorizationGate(store, () -> this.isEnabled, clock::nowMs, listener,
                TIMEOUT_MS);
        }
    }

    private static final class RecordingListener implements AuthorizationGate.Listener {
        private final List<ApprovalRequest> requested = new ArrayList<>();

        private final List<ApprovalOutcome> resolved = new ArrayList<>();

        private final List<ApprovalRequest> cancelled = new ArrayList<>();

        @Override
        public void onApprovalRequested(@NonNull final ApprovalRequest request) {
            requested.add(request);
        }

        @Override
        public void onApprovalResolved(@NonNull final ApprovalRequest request,
                                       @NonNull final ApprovalOutcome outcome) {
            resolved.add(outcome);
        }

        @Override
        public void onApprovalCancelled(@NonNull final ApprovalRequest request) {
            cancelled.add(request);
        }
    }
}
