package com.obby.android.localscreenshare.discovery.register;

import com.obby.android.localscreenshare.discovery.model.DeviceAddress;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.support.FakeDelayedScheduler;
import com.obby.android.localscreenshare.discovery.support.FakeRegisterStub;
import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterResponse;
import com.obby.android.localscreenshare.support.BackoffPolicy;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RegisterClientTest {

    private static final String DEVICE_ID = "L7Q3K9M2F4X8B6N1C5V0Z3T7R2";

    private FakeRegisterStub mStub;

    private FakeDelayedScheduler mScheduler;

    private RegisterClient mClient;

    private RecordingCallback mCallback;

    private DeviceAddress mAddress;

    private DeviceSummary mSelf;

    @Before
    public void setUp() {
        mStub = new FakeRegisterStub();
        mScheduler = new FakeDelayedScheduler();
        mClient = new RegisterClient(mStub, mScheduler, Runnable::run, BackoffPolicy.NO_JITTER);
        mCallback = new RecordingCallback();
        mAddress = new DeviceAddress("10.0.0.5", 8080, DiscoveryChannel.MULTICAST);
        mSelf = DeviceSummary.newBuilder()
            .setDeviceId(DEVICE_ID)
            .setFingerprint("fingerprint-value")
            .build();
    }

    @Test
    public void acceptedResponseCompletesTheCall() {
        assertTrue(mClient.register(mAddress, true, mSelf, mCallback));

        assertEquals(1, mStub.callCount());
        assertEquals(RegisterConstants.REGISTER_TIMEOUT_MS, mStub.last().timeoutMs);
        assertEquals(1, mStub.last().request.getProtocolVersion());
        assertTrue(mStub.last().request.getAnnounces());

        mStub.respondLast(accepted());

        assertEquals(1, mCallback.successes.size());
        assertEquals(0, mCallback.failures.size());
        assertEquals(0, mClient.pendingCount());
    }

    @Test
    public void timeoutRetriesTwiceThenFails() {
        mClient.register(mAddress, true, mSelf, mCallback);

        mScheduler.advance(RegisterConstants.REGISTER_TIMEOUT_MS);
        assertEquals(1, mStub.callCount());
        mScheduler.advance(RegisterConstants.REGISTER_RETRY_DELAYS_MS[0]);
        assertEquals(2, mStub.callCount());
        mScheduler.advance(RegisterConstants.REGISTER_TIMEOUT_MS);
        mScheduler.advance(RegisterConstants.REGISTER_RETRY_DELAYS_MS[1]);
        assertEquals(3, mStub.callCount());
        mScheduler.advance(RegisterConstants.REGISTER_TIMEOUT_MS);

        assertEquals(0, mCallback.successes.size());
        assertEquals(1, mCallback.failures.size());
        assertEquals("timeout", mCallback.failures.get(0));
        assertEquals(0, mClient.pendingCount());
    }

    @Test
    public void transportFailureIsRetried() {
        mClient.register(mAddress, true, mSelf, mCallback);

        mStub.failLast("UNAVAILABLE");
        assertEquals(1, mStub.callCount());

        mScheduler.advance(RegisterConstants.REGISTER_RETRY_DELAYS_MS[0]);

        assertEquals(2, mStub.callCount());
        assertEquals(0, mCallback.failures.size());
    }

    @Test
    public void rejectionIsTerminalAndDoesNotRetry() {
        mClient.register(mAddress, true, mSelf, mCallback);

        mStub.respondLast(RegisterResponse.newBuilder()
            .setProtocolVersion(1)
            .setAccepted(false)
            .setRejectReason("rate_limited")
            .build());
        mScheduler.advance(30_000L);

        assertEquals(1, mStub.callCount());
        assertEquals("rate_limited", mCallback.failures.get(0));
    }

    @Test
    public void acceptedResponseWithoutSelfIdIsAFailure() {
        mClient.register(mAddress, true, mSelf, mCallback);

        mStub.respondLast(RegisterResponse.newBuilder()
            .setProtocolVersion(1)
            .setAccepted(true)
            .build());

        assertEquals("missing_identity", mCallback.failures.get(0));
        assertTrue(mCallback.successes.isEmpty());
    }

    @Test
    public void duplicateAddressIsIgnoredWhileTheCallIsInFlight() {
        assertTrue(mClient.register(mAddress, true, mSelf, mCallback));
        assertFalse(mClient.register(mAddress, true, mSelf, mCallback));

        assertEquals(1, mStub.callCount());
        assertEquals(1, mClient.pendingCount());
    }

    @Test
    public void aLateTimeoutAfterSuccessIsIgnored() {
        mClient.register(mAddress, true, mSelf, mCallback);
        mStub.respondLast(accepted());

        mScheduler.advance(30_000L);

        assertEquals(1, mCallback.successes.size());
        assertTrue(mCallback.failures.isEmpty());
        assertEquals(1, mStub.callCount());
    }

    @Test
    public void cancelAllSuppressesPendingCallbacks() {
        mClient.register(mAddress, true, mSelf, mCallback);

        mClient.cancelAll();
        mScheduler.advance(30_000L);

        assertEquals(0, mClient.pendingCount());
        assertTrue(mCallback.successes.isEmpty());
        assertTrue(mCallback.failures.isEmpty());
        assertEquals(1, mStub.callCount());
    }

    @Test
    public void reverseRegisterCarriesAnnouncesFalse() {
        mClient.register(mAddress, false, mSelf, mCallback);

        assertFalse(mStub.last().request.getAnnounces());
        assertTrue(mCallback.failures.isEmpty());
    }

    private static RegisterResponse accepted() {
        return RegisterResponse.newBuilder()
            .setProtocolVersion(1)
            .setAccepted(true)
            .setSelf(DeviceSummary.newBuilder()
                .setDeviceId(DEVICE_ID)
                .setFingerprint("fingerprint-value")
                .build())
            .build();
    }

    /** Records callbacks; both lists stay empty until one arrives. */
    private static final class RecordingCallback implements RegisterClient.Callback {
        private final List<RegisterResponse> successes = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();

        @Override
        public void onSuccess(RegisterResponse response) {
            successes.add(response);
        }

        @Override
        public void onFailure(String reason) {
            failures.add(reason);
        }
    }
}
