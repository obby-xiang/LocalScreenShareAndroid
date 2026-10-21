package com.obby.android.localscreenshare.server;

import com.obby.android.localscreenshare.core.support.MutableClock;
import com.obby.android.localscreenshare.discovery.model.DevicePresence;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.model.DeviceInfo;
import com.obby.android.localscreenshare.discovery.register.RegisterClient;
import com.obby.android.localscreenshare.discovery.register.RegisterConstants;
import com.obby.android.localscreenshare.discovery.store.DeviceStore;
import com.obby.android.localscreenshare.discovery.support.FakeDelayedScheduler;
import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterRequest;
import com.obby.android.localscreenshare.identity.tls.TlsCertificate;
import com.obby.android.localscreenshare.identity.tls.TlsMaterial;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterResponse;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RegisterCallHandlerTest {

    private static final String PEER_ID = "L7Q3K9M2F4X8B6N1C5V0Z3T7R2";

    private static final String SELF_ID = "AAAAAAAAAAAAAAAAAAAAAAAAAA";

    private static final String FINGERPRINT = "peer-fingerprint";

    private static final String REMOTE_HOST = "10.0.0.5";

    private MutableClock mClock;

    private DeviceStore mStore;

    private FakeDelayedScheduler mScheduler;

    private List<ReverseCall> mReverseCalls;

    private RegisterCallHandler mHandler;

    private DeviceSummary mSelf;

    @Before
    public void setUp() {
        mClock = new MutableClock(100_000L);
        mStore = new DeviceStore(new MutableClock(10_000L));
        mScheduler = new FakeDelayedScheduler();
        mReverseCalls = new ArrayList<>();
        mSelf = DeviceSummary.newBuilder()
            .setDeviceId(SELF_ID)
            .setFingerprint("self-fingerprint")
            .setRole("server")
            .build();
        mHandler = new RegisterCallHandler(() -> mSelf, mStore, Runnable::run, mClock,
            (address, announces, self, callback) -> mReverseCalls.add(
                new ReverseCall(address.getHost(), address.getPort(), announces)),
            mScheduler, new Random(7L), DiscoveryChannel.MULTICAST);
    }

    @Test
    public void validRequestIsAcceptedLearnedAndAnsweredWithTheServerSummary() {
        AtomicReference<RegisterResponse> response = new AtomicReference<>();

        mHandler.handle(request(true), REMOTE_HOST, response::set);

        assertNotNull(response.get());
        assertTrue(response.get().getAccepted());
        assertEquals(SELF_ID, response.get().getSelf().getDeviceId());

        DeviceInfo device = mStore.device(PEER_ID);
        assertNotNull(device);
        assertEquals(FINGERPRINT, device.getFingerprint());
        assertEquals(DevicePresence.CONFIRMED, device.getPresence());
        assertTrue(device.primaryAddress().isConfirmed());
        assertEquals(REMOTE_HOST, device.primaryAddress().getHost());
        assertEquals(8080, device.primaryAddress().getPort());
    }

    @Test
    public void announcesTrueSendsAtMostOneReverseRegister() {
        mHandler.handle(request(true), REMOTE_HOST, response -> { });

        mScheduler.advance(1_000L);

        assertEquals(1, mReverseCalls.size());
        assertEquals(REMOTE_HOST, mReverseCalls.get(0).host);
        assertEquals(8080, mReverseCalls.get(0).port);
        assertFalse(mReverseCalls.get(0).announces);
    }

    @Test
    public void announcesFalseEndsTheChain() {
        mHandler.handle(request(false), REMOTE_HOST, response -> { });

        mScheduler.advance(1_000L);

        assertTrue(mReverseCalls.isEmpty());
    }

    @Test
    public void missingIdentityIsRejected() {
        RegisterResponse response = handle(RegisterRequest.newBuilder()
            .setProtocolVersion(1)
            .setAnnounces(true)
            .setSelf(DeviceSummary.newBuilder().setName("no id").build())
            .build());

        assertFalse(response.getAccepted());
        assertEquals(RegisterCallHandler.REASON_MISSING_IDENTITY, response.getRejectReason());
        assertEquals(0, mStore.size());
        assertTrue(mReverseCalls.isEmpty());
    }

    @Test
    public void malformedIdentityIsRejected() {
        RegisterResponse response = handle(requestBuilder()
            .setSelf(DeviceSummary.newBuilder()
                .setDeviceId("short")
                .setFingerprint(FINGERPRINT)
                .build())
            .build());

        assertFalse(response.getAccepted());
        assertEquals(RegisterCallHandler.REASON_INVALID_IDENTITY, response.getRejectReason());
        assertEquals(0, mStore.size());
    }

    @Test
    public void missingFingerprintIsRejected() {
        RegisterResponse response = handle(RegisterRequest.newBuilder()
            .setProtocolVersion(1)
            .setSelf(DeviceSummary.newBuilder().setDeviceId(PEER_ID).build())
            .build());

        assertFalse(response.getAccepted());
        assertEquals(RegisterCallHandler.REASON_MISSING_IDENTITY, response.getRejectReason());
    }

    @Test
    public void unsupportedVersionIsRejected() {
        RegisterResponse response = handle(requestBuilder().setProtocolVersion(2).build());

        assertFalse(response.getAccepted());
        assertEquals(RegisterCallHandler.REASON_UNSUPPORTED_VERSION, response.getRejectReason());
    }

    @Test
    public void invalidAddressIsRejected() {
        RegisterResponse response = handle(requestBuilder()
            .setSelf(DeviceSummary.newBuilder()
                .setDeviceId(PEER_ID)
                .setFingerprint(FINGERPRINT)
                .addAddresses(com.obby.android.localscreenshare.grpc.screenstream.DeviceAddress.newBuilder()
                    .setHost("10.0.0.5")
                    .setPort(0)
                    .build())
                .build())
            .build());

        assertFalse(response.getAccepted());
        assertEquals(RegisterCallHandler.REASON_INVALID_REQUEST, response.getRejectReason());
    }

    @Test
    public void requestsPerAddressAreRateLimited() {
        for (int i = 0; i < RegisterConstants.REGISTER_REQUESTS_PER_ADDRESS_PER_MINUTE; i++) {
            RegisterResponse accepted = handle(request(true));
            assertTrue("request " + i + " should be accepted", accepted.getAccepted());
        }

        RegisterResponse limited = handle(request(true));

        assertFalse(limited.getAccepted());
        assertEquals(RegisterCallHandler.REASON_RATE_LIMITED, limited.getRejectReason());
    }

    @Test
    public void unavailableServerRejectsWithoutLearning() {
        RegisterCallHandler handler = new RegisterCallHandler(() -> null, mStore, Runnable::run, mClock);

        AtomicReference<RegisterResponse> response = new AtomicReference<>();
        handler.handle(request(true), REMOTE_HOST, response::set);

        assertFalse(response.get().getAccepted());
        assertEquals(RegisterCallHandler.REASON_UNAVAILABLE, response.get().getRejectReason());
        assertEquals(0, mStore.size());
    }

    @Test
    public void reverseQuotaLimitsTheNumberOfUnsolicitedCalls() {
        for (int i = 0; i < RegisterConstants.REGISTER_REVERSE_PER_MINUTE + 10; i++) {
            mHandler.handle(request(true), "10.0.1." + i, response -> { });
        }
        mScheduler.advance(1_000L);

        assertEquals(RegisterConstants.REGISTER_REVERSE_PER_MINUTE, mReverseCalls.size());
    }

    @Test
    public void handlerWithoutStoreStillAnswers() {
        RegisterCallHandler handler = new RegisterCallHandler(() -> mSelf, null, Runnable::run, mClock);

        AtomicReference<RegisterResponse> response = new AtomicReference<>();
        handler.handle(request(true), REMOTE_HOST, response::set);

        assertTrue(response.get().getAccepted());
        assertNull(mStore.device(PEER_ID));
    }

    @Test
    public void callerFilterRejectsHiddenDeviceAndDoesNotLearn() {
        RegisterCallHandler handler = filteredHandler(id -> false);

        AtomicReference<RegisterResponse> response = new AtomicReference<>();
        handler.handle(request(true), REMOTE_HOST, response::set);

        assertNotNull(response.get());
        assertFalse(response.get().getAccepted());
        assertEquals(RegisterCallHandler.REASON_NOT_VISIBLE, response.get().getRejectReason());
        assertEquals(0, mStore.size());
        assertTrue(mReverseCalls.isEmpty());
    }

    @Test
    public void callerFilterAcceptsAFavoriteCaller() {
        RegisterCallHandler handler = filteredHandler(id -> id.equals(PEER_ID));

        AtomicReference<RegisterResponse> response = new AtomicReference<>();
        handler.handle(request(true), REMOTE_HOST, response::set);

        assertTrue(response.get().getAccepted());
        assertNotNull(mStore.device(PEER_ID));
    }

    @Test
    public void selfRegisterIsAnsweredButNotLearned() {
        AtomicReference<RegisterResponse> response = new AtomicReference<>();
        mHandler.handle(RegisterRequest.newBuilder()
            .setProtocolVersion(1)
            .setAnnounces(true)
            .setSelf(DeviceSummary.newBuilder()
                .setDeviceId(SELF_ID)
                .setFingerprint("self-fingerprint")
                .setName("me")
                .setRole("server")
                .build())
            .build(), REMOTE_HOST, response::set);

        assertNotNull(response.get());
        assertTrue(response.get().getAccepted());
        assertEquals(0, mStore.size());
    }

    @Test
    public void callerWithoutAddressIsNotLearned() {
        AtomicReference<RegisterResponse> response = new AtomicReference<>();
        mHandler.handle(RegisterRequest.newBuilder()
            .setProtocolVersion(1)
            .setAnnounces(true)
            .setSelf(DeviceSummary.newBuilder()
                .setDeviceId(PEER_ID)
                .setFingerprint(FINGERPRINT)
                .setName("viewer")
                .setRole("client")
                .build())
            .build(), REMOTE_HOST, response::set);

        assertNotNull(response.get());
        assertTrue(response.get().getAccepted());
        assertEquals(0, mStore.size());
    }

    @Test
    public void certificateOfAnotherIdIsRejected() throws Exception {
        TlsCertificate certificate = certificate();
        AtomicReference<RegisterResponse> response = new AtomicReference<>();

        mHandler.handle(request(true), REMOTE_HOST, certificate, response::set);

        assertNotNull(response.get());
        assertFalse(response.get().getAccepted());
        assertEquals(RegisterCallHandler.REASON_IDENTITY_MISMATCH,
            response.get().getRejectReason());
        assertNull(mStore.device(PEER_ID));
    }

    @Test
    public void certificateFingerprintOverridesTheBodyFingerprint() throws Exception {
        TlsCertificate certificate = certificate();
        RegisterRequest request = requestBuilder()
            .setSelf(peerSummary(certificate.getDeviceId(), "body-fingerprint"))
            .build();
        AtomicReference<RegisterResponse> response = new AtomicReference<>();

        mHandler.handle(request, REMOTE_HOST, certificate, response::set);

        assertNotNull(response.get());
        assertTrue(response.get().getAccepted());
        DeviceInfo device = mStore.device(certificate.getDeviceId());
        assertNotNull(device);
        assertEquals(certificate.getFingerprint(), device.getFingerprint());
    }

    private static DeviceSummary peerSummary(final String deviceId, final String fingerprint) {
        return DeviceSummary.newBuilder()
            .setDeviceId(deviceId)
            .setFingerprint(fingerprint)
            .addAddresses(com.obby.android.localscreenshare.grpc.screenstream.DeviceAddress.newBuilder()
                .setHost(REMOTE_HOST)
                .setPort(8080)
                .setFamily(4)
                .build())
            .build();
    }

    private static TlsCertificate certificate() throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        java.security.KeyPair keyPair = generator.generateKeyPair();
        return TlsMaterial.fromKeyPair(keyPair).getCertificate();
    }

    private RegisterCallHandler filteredHandler(java.util.function.Predicate<String> filter) {
        return new RegisterCallHandler(() -> mSelf, mStore, Runnable::run, mClock,
            (address, announces, self, callback) -> mReverseCalls.add(
                new ReverseCall(address.getHost(), address.getPort(), announces)),
            mScheduler, new Random(7L), DiscoveryChannel.MULTICAST, filter);
    }

    private RegisterResponse handle(RegisterRequest request) {
        AtomicReference<RegisterResponse> response = new AtomicReference<>();
        mHandler.handle(request, REMOTE_HOST, response::set);
        assertNotNull(response.get());
        return response.get();
    }

    private RegisterRequest request(boolean announces) {
        return requestBuilder().setAnnounces(announces).build();
    }

    private static RegisterRequest.Builder requestBuilder() {
        return RegisterRequest.newBuilder()
            .setProtocolVersion(1)
            .setAnnounces(true)
            .setSelf(DeviceSummary.newBuilder()
                .setDeviceId(PEER_ID)
                .setFingerprint(FINGERPRINT)
                .setName("Living room")
                .setRole("client")
                .addAddresses(com.obby.android.localscreenshare.grpc.screenstream.DeviceAddress.newBuilder()
                    .setHost(REMOTE_HOST)
                    .setPort(8080)
                    .setFamily(4)
                    .build())
                .build());
    }

    /**
     * One reverse call recorded by the fake registrar.
     */
    private static final class ReverseCall {
        final String host;

        final int port;

        final boolean announces;

        private ReverseCall(String host, int port, boolean announces) {
            this.host = host;
            this.port = port;
            this.announces = announces;
        }
    }
}
