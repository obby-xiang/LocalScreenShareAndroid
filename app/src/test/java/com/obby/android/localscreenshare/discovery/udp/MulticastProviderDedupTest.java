package com.obby.android.localscreenshare.discovery.udp;

import com.obby.android.localscreenshare.core.support.MutableClock;
import com.obby.android.localscreenshare.discovery.model.DeviceRole;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.model.ShareState;
import com.obby.android.localscreenshare.discovery.provider.DeviceObservation;
import com.obby.android.localscreenshare.discovery.provider.DiscoveryProviderListener;
import com.obby.android.localscreenshare.discovery.register.RegisterClient;
import com.obby.android.localscreenshare.discovery.support.FakeDelayedScheduler;
import com.obby.android.localscreenshare.discovery.support.FakeMulticastTransport;
import com.obby.android.localscreenshare.discovery.support.FakeRegisterStub;
import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterResponse;
import com.obby.android.localscreenshare.support.BackoffPolicy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MulticastProviderDedupTest {

    private static final String SELF_ID = "AAAAAAAAAAAAAAAAAAAAAAAAAA";

    private static final String PEER_ID = "L7Q3K9M2F4X8B6N1C5V0Z3T7R2";

    private static final String REMOTE_HOST = "10.0.0.5";

    private static final String BOOT = "b7f3c2a1d4e59608";

    private MutableClock mClock;

    private FakeMulticastTransport mTransport;

    private FakeRegisterStub mStub;

    private FakeDelayedScheduler mRegisterScheduler;

    private MulticastAnnouncementFilter mFilter;

    private UdpMulticastDiscoveryProvider mProvider;

    private RecordingListener mListener;

    private List<DeviceSummary> mConfirmed;

    private MulticastLockManager mLockManager;

    @Before
    public void setUp() throws Exception {
        mClock = new MutableClock(10_000L);
        mTransport = new FakeMulticastTransport();
        mStub = new FakeRegisterStub();
        mRegisterScheduler = new FakeDelayedScheduler();
        mFilter = new MulticastAnnouncementFilter(id -> id.equals(SELF_ID), mClock);
        mListener = new RecordingListener();
        mConfirmed = new ArrayList<>();

        final AtomicBoolean lockHeld = new AtomicBoolean();
        mLockManager = new MulticastLockManager(new MulticastLockManager.Lock() {
            @Override
            public void acquire() {
                lockHeld.set(true);
            }

            @Override
            public void release() {
                lockHeld.set(false);
            }
        });
        final RegisterClient registerClient = new RegisterClient(mStub, mRegisterScheduler,
            Runnable::run, BackoffPolicy.NO_JITTER);
        mProvider = new UdpMulticastDiscoveryProvider(() -> mTransport, mLockManager,
            id -> id.equals(SELF_ID), mClock, Runnable::run, registerClient, this::selfSummary,
            (peer, host, port) -> mConfirmed.add(peer));
        mProvider.start(mListener);
        assertTrue(lockHeld.get());
    }

    @After
    public void tearDown() {
        mProvider.stop();
    }

    @Test
    public void acceptedAnnouncementIsObservedAndTriggersRegister() throws Exception {
        deliver(announcement(PEER_ID, BOOT, 1L, true));

        assertEquals(1, mListener.observations.size());
        DeviceObservation observation = mListener.observations.get(0);
        assertEquals(DiscoveryChannel.MULTICAST, observation.getSource());
        assertEquals(PEER_ID, observation.getId());
        assertEquals(REMOTE_HOST, observation.getHost());
        assertEquals(Integer.valueOf(8080), observation.getPort());
        assertFalse(observation.isGoodbye());

        assertEquals(1, mStub.callCount());
        assertEquals(8080, mStub.last().address.getPort());
        assertEquals(REMOTE_HOST, mStub.last().address.getHost());
        assertTrue(mStub.last().request.getAnnounces());
        assertEquals(SELF_ID, mStub.last().request.getSelf().getDeviceId());
    }

    @Test
    public void duplicateAndOlderSequencesAreDropped() throws Exception {
        deliver(announcement(PEER_ID, BOOT, 5L, true));
        deliver(announcement(PEER_ID, BOOT, 5L, true));
        deliver(announcement(PEER_ID, BOOT, 4L, true));

        assertEquals(1, mListener.observations.size());
        assertEquals(1, mStub.callCount());

        deliver(announcement(PEER_ID, BOOT, 6L, true));
        assertEquals(2, mListener.observations.size());
    }

    @Test
    public void aNewBootResetsTheSequenceBaseline() throws Exception {
        deliver(announcement(PEER_ID, BOOT, 10L, true));
        deliver(announcement(PEER_ID, "0011223344556677", 1L, true));

        assertEquals(2, mListener.observations.size());
    }

    @Test
    public void ownAnnouncementIsFilteredById() throws Exception {
        deliver(announcement(SELF_ID, BOOT, 1L, true));

        assertEquals(0, mListener.observations.size());
        assertEquals(0, mStub.callCount());
    }

    @Test
    public void registerIsMergedPerAddressWithinTheWindow() throws Exception {
        deliver(announcement(PEER_ID, BOOT, 1L, true));
        respondAccepted();
        assertEquals(1, mStub.callCount());

        mClock.advance(299L);
        deliver(announcement(PEER_ID, BOOT, 2L, true));
        assertEquals(2, mListener.observations.size());
        assertEquals(1, mStub.callCount());

        mClock.advance(1L);
        deliver(announcement(PEER_ID, BOOT, 3L, true));
        assertEquals(3, mListener.observations.size());
        assertEquals(2, mStub.callCount());
    }

    @Test
    public void successfulRegisterConfirmsThePeer() throws Exception {
        deliver(announcement(PEER_ID, BOOT, 1L, true));

        respondAccepted();

        assertEquals(1, mConfirmed.size());
        assertEquals(PEER_ID, mConfirmed.get(0).getDeviceId());
        assertEquals("fingerprint-value", mConfirmed.get(0).getFingerprint());
    }

    @Test
    public void rejectedRegisterDoesNotConfirm() throws Exception {
        deliver(announcement(PEER_ID, BOOT, 1L, true));

        mStub.respondLast(RegisterResponse.newBuilder()
            .setProtocolVersion(1)
            .setAccepted(false)
            .setRejectReason("rate_limited")
            .build());

        assertEquals(0, mConfirmed.size());
    }

    @Test
    public void goodbyeIsDeliveredAndNeverRegisters() throws Exception {
        deliver(announcement(PEER_ID, BOOT, 9L, false));

        assertEquals(1, mListener.observations.size());
        assertTrue(mListener.observations.get(0).isGoodbye());
        assertEquals(0, mStub.callCount());
    }

    @Test
    public void stopClosesTheTransportAndReleasesTheLock() {
        assertEquals(1, mLockManager.refCount());

        mProvider.stop();

        assertTrue(mTransport.isClosed());
        assertEquals(0, mLockManager.refCount());
    }

    @Test
    public void orderingRulesAreAlsoCoveredDirectlyByTheFilter() {
        Announcement first = announcement(PEER_ID, BOOT, 3L, true);
        assertTrue(mFilter.accept(first));
        assertFalse(mFilter.accept(announcement(PEER_ID, BOOT, 3L, true)));
        assertFalse(mFilter.accept(announcement(PEER_ID, BOOT, 2L, true)));
        assertTrue(mFilter.accept(announcement(PEER_ID, BOOT, 4L, true)));
        assertTrue(mFilter.accept(announcement(PEER_ID, "0011223344556677", 1L, true)));
        assertFalse(mFilter.accept(announcement(SELF_ID, BOOT, 99L, true)));
    }

    private void deliver(Announcement announcement) throws Exception {
        mTransport.deliver(AnnouncementCodec.encode(announcement),
            InetAddress.getByName(REMOTE_HOST));
    }

    private void respondAccepted() {
        mStub.respondLast(RegisterResponse.newBuilder()
            .setProtocolVersion(1)
            .setAccepted(true)
            .setSelf(DeviceSummary.newBuilder()
                .setDeviceId(PEER_ID)
                .setFingerprint("fingerprint-value")
                .setRole("server")
                .build())
            .build());
    }

    private Announcement announcement(String id, String boot, long seq, boolean announces) {
        return Announcement.builder()
            .id(id)
            .role(DeviceRole.SERVER)
            .state(ShareState.LIVE)
            .port(8080)
            .boot(boot)
            .seq(seq)
            .ttlSeconds(5)
            .announces(announces)
            .build();
    }

    private DeviceSummary selfSummary() {
        return DeviceSummary.newBuilder()
            .setDeviceId(SELF_ID)
            .setFingerprint("self-fingerprint")
            .build();
    }

    private static final class RecordingListener implements DiscoveryProviderListener {
        private final List<DeviceObservation> observations = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();

        @Override
        public void onObserved(DeviceObservation observation) {
            observations.add(observation);
        }

        @Override
        public void onProviderError(String reason) {
            errors.add(reason);
        }
    }
}
