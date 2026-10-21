package com.obby.android.localscreenshare.discovery.manual;

import com.obby.android.localscreenshare.discovery.favorite.FavoriteDevice;
import com.obby.android.localscreenshare.discovery.favorite.FavoriteStore;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.provider.DiscoveryProviderListener;
import com.obby.android.localscreenshare.discovery.support.FakeFavoriteStorage;
import com.obby.android.localscreenshare.discovery.support.FakeProbe;
import com.obby.android.localscreenshare.discovery.scan.ProbeResult;
import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ManualAddressProviderTest {

    private static final long TIMEOUT_MS = 2_000L;

    // Wider deadline used until one probe was answered; the production values come from
    // ScanConstants.
    private static final long COLD_TIMEOUT_MS = 5_000L;

    private FakeProbe mProbe;

    private FavoriteStore mFavorites;

    private RecordingSink mSink;

    private RecordingCallback mCallback;

    private ManualAddressProvider mProvider;

    @Before
    public void setUp() {
        mProbe = FakeProbe.confirmingAll("remote-device");
        mFavorites = new FavoriteStore(new FakeFavoriteStorage());
        mSink = new RecordingSink();
        mCallback = new RecordingCallback();
        mProvider = new ManualAddressProvider(mProbe, mFavorites, Runnable::run, mSink, TIMEOUT_MS);
    }

    @After
    public void tearDown() {
        mProvider.shutdown();
    }

    @Test
    public void favoritesAreProbedAndConfirmedAtStart() throws InterruptedException {
        mFavorites.add(FavoriteDevice.create("ID-A", "fp-a", "10.0.0.5", 8080, "Kitchen", false));
        mFavorites.add(FavoriteDevice.create("ID-B", "fp-b", "10.0.0.6", 9090, "Office", false));

        mProvider.start(noopListener());

        waitFor(() -> mProbe.callCount() == 2);
        waitFor(() -> mSink.confirmations.size() == 2);

        assertTrue(mProbe.hosts().contains("10.0.0.5"));
        assertTrue(mProbe.hosts().contains("10.0.0.6"));
        assertEquals(2, mSink.confirmations.size());
        assertEquals(DiscoveryChannel.FAVORITE, mSink.confirmations.get(0).channel);
    }

    @Test
    public void favoritesWithoutAnAddressAreNotProbed() throws InterruptedException {
        mFavorites.add(FavoriteDevice.create("ID-A", null, "10.0.0.5", 0, null, false));

        mProvider.start(noopListener());
        Thread.sleep(100L);

        assertEquals(0, mProbe.callCount());
        assertTrue(mSink.confirmations.isEmpty());
    }

    @Test
    public void stopSuppressesLateConfirmations() throws InterruptedException {
        mFavorites.add(FavoriteDevice.create("ID-A", "fp-a", "10.0.0.5", 8080, null, false));
        mProbe.setDelayMs(50L);

        mProvider.start(noopListener());
        mProvider.stop();
        Thread.sleep(200L);

        assertTrue(mSink.confirmations.isEmpty());
    }

    @Test
    public void aManualProbeConfirmsThroughTheSink() throws InterruptedException {
        assertTrue(mProvider.probeManual("192.168.1.9:9000", 8080, mCallback));

        waitFor(() -> mCallback.confirmed.size() == 1);

        assertEquals(1, mSink.confirmations.size());
        assertEquals(DiscoveryChannel.MANUAL, mSink.confirmations.get(0).channel);
        assertEquals("192.168.1.9", mSink.confirmations.get(0).host);
        assertEquals(9000, mSink.confirmations.get(0).port);
        assertEquals("192.168.1.9", mCallback.confirmed.get(0));
    }

    @Test
    public void aManualProbeUsesTheDefaultPort() throws InterruptedException {
        assertTrue(mProvider.probeManual("192.168.1.9", 8080, mCallback));

        waitFor(() -> mCallback.confirmed.size() == 1);

        assertEquals(8080, mProbe.calls().get(0).port);
    }

    @Test
    public void anInvalidAddressIsRejectedBeforeProbing() {
        assertFalse(mProvider.probeManual("not-an-address", 8080, mCallback));

        assertEquals("invalid_address", mCallback.failures.get(0));
        assertEquals(0, mProbe.callCount());
        assertTrue(mSink.confirmations.isEmpty());
    }

    @Test
    public void confirmedManualTargetsAreReprobed() throws InterruptedException {
        mProvider.start(noopListener());
        assertTrue(mProvider.probeManual("192.168.1.9:9000", 8080, mCallback));
        waitFor(() -> mCallback.confirmed.size() == 1);

        final int callsAfterFirstProbe = mProbe.callCount();
        mProvider.probeManualTargets();
        waitFor(() -> mSink.confirmations.size() == 2);

        assertTrue(mProbe.callCount() > callsAfterFirstProbe);
        assertTrue(mProbe.hosts().contains("192.168.1.9"));
        assertEquals(2, mSink.confirmations.size());
        assertEquals(DiscoveryChannel.MANUAL, mSink.confirmations.get(1).channel);
    }

    @Test
    public void reprobeIsSuppressedWhenStopped() throws InterruptedException {
        mProvider.start(noopListener());
        assertTrue(mProvider.probeManual("192.168.1.9", 8080, mCallback));
        waitFor(() -> mCallback.confirmed.size() == 1);
        mProvider.stop();

        final int calls = mProbe.callCount();
        mProvider.probeManualTargets();
        Thread.sleep(100L);

        assertEquals(calls, mProbe.callCount());
    }

    @Test
    public void failedReprobe_reportsToTheUnreachableSink() throws InterruptedException {
        final int[] calls = new int[]{0};
        FakeProbe probe = new FakeProbe((host, port) -> {
            calls[0]++;
            if (calls[0] == 1) {
                return ProbeResult.confirmed(DeviceSummary.newBuilder()
                    .setDeviceId("remote-device")
                    .setFingerprint("fingerprint")
                    .build(), host, port);
            }
            return ProbeResult.failed(host, port, "unreachable");
        });

        List<String> unreachable = new CopyOnWriteArrayList<>();
        ManualAddressProvider provider = new ManualAddressProvider(probe, mFavorites, Runnable::run,
            mSink, TIMEOUT_MS);
        provider.setUnreachableSink((host, port) -> unreachable.add(host + ":" + port));

        provider.start(noopListener());
        assertTrue(provider.probeManual("192.168.1.9:9000", 8080, mCallback));
        waitFor(() -> mCallback.confirmed.size() == 1);

        provider.probeManualTargets();
        waitFor(() -> !unreachable.isEmpty());

        assertEquals(Collections.singletonList("192.168.1.9:9000"), unreachable);
        provider.shutdown();
    }

    @Test
    public void aFailedProbeIsReportedWithoutAConfirmation() throws InterruptedException {
        mProvider.shutdown();
        mProbe = FakeProbe.alwaysFailing();
        mProvider = new ManualAddressProvider(mProbe, mFavorites, Runnable::run, mSink, TIMEOUT_MS);

        assertTrue(mProvider.probeManual("192.168.1.9", 8080, mCallback));

        waitFor(() -> !mCallback.failures.isEmpty());

        assertEquals("unreachable", mCallback.failures.get(0));
        assertTrue(mSink.confirmations.isEmpty());
    }

    @Test
    public void theColdDeadlineAppliesUntilOneProbeIsAnswered() throws InterruptedException {
        mProvider.shutdown();
        mProbe = FakeProbe.confirmingAll("remote-device");
        mProvider = new ManualAddressProvider(mProbe, mFavorites, Runnable::run, mSink,
            TIMEOUT_MS, COLD_TIMEOUT_MS);

        assertTrue(mProvider.probeManual("192.168.1.9", 8080, mCallback));
        waitFor(() -> mCallback.confirmed.size() == 1);
        assertTrue(mProvider.probeManual("192.168.1.10", 8080, mCallback));
        waitFor(() -> mCallback.confirmed.size() == 2);

        assertEquals(COLD_TIMEOUT_MS, mProbe.calls().get(0).timeoutMs);
        assertEquals(TIMEOUT_MS, mProbe.calls().get(1).timeoutMs);
    }

    @Test
    public void unansweredProbesKeepTheColdDeadline() throws InterruptedException {
        mProvider.shutdown();
        mProbe = FakeProbe.alwaysFailing();
        mProvider = new ManualAddressProvider(mProbe, mFavorites, Runnable::run, mSink,
            TIMEOUT_MS, COLD_TIMEOUT_MS);

        assertTrue(mProvider.probeManual("192.168.1.9", 8080, mCallback));
        waitFor(() -> mCallback.failures.size() == 1);
        assertTrue(mProvider.probeManual("192.168.1.10", 8080, mCallback));
        waitFor(() -> mCallback.failures.size() == 2);

        assertEquals(COLD_TIMEOUT_MS, mProbe.calls().get(0).timeoutMs);
        assertEquals(COLD_TIMEOUT_MS, mProbe.calls().get(1).timeoutMs);
    }

    @Test
    public void aProbeThatThrowsStillReportsAFailure() throws InterruptedException {
        mProvider.shutdown();
        mProbe = new FakeProbe((host, port) -> {
            throw new IllegalStateException("transport broke");
        });
        mProvider = new ManualAddressProvider(mProbe, mFavorites, Runnable::run, mSink, TIMEOUT_MS);

        assertTrue(mProvider.probeManual("192.168.1.9", 8080, mCallback));

        waitFor(() -> !mCallback.failures.isEmpty());

        assertEquals("probe_failed", mCallback.failures.get(0));
        assertTrue(mSink.confirmations.isEmpty());
    }

    private static DiscoveryProviderListener noopListener() {
        return new DiscoveryProviderListener() {
            @Override
            public void onObserved(
                @androidx.annotation.NonNull com.obby.android.localscreenshare.discovery.provider.DeviceObservation observation) {
            }

            @Override
            public void onProviderError(@androidx.annotation.NonNull String reason) {
            }
        };
    }

    private static void waitFor(final Condition condition) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 2_000L;
        while (!condition.isMet() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5L);
        }
        assertTrue("condition not met in time", condition.isMet());
    }

    @FunctionalInterface
    private interface Condition {
        boolean isMet();
    }

    private static final class RecordingSink implements ManualAddressProvider.ConfirmationSink {
        private final List<Confirmation> confirmations = new CopyOnWriteArrayList<>();

        @Override
        public void onConfirmed(final DiscoveryChannel channel, final DeviceSummary peer,
            final String host, final int port) {
            confirmations.add(new Confirmation(channel, host, port));
        }
    }

    private static final class Confirmation {
        private final DiscoveryChannel channel;
        private final String host;
        private final int port;

        private Confirmation(final DiscoveryChannel channel, final String host, final int port) {
            this.channel = channel;
            this.host = host;
            this.port = port;
        }
    }

    private static final class RecordingCallback implements ManualAddressProvider.ProbeCallback {
        private final List<String> confirmed = new CopyOnWriteArrayList<>();
        private final List<String> failures = new CopyOnWriteArrayList<>();

        @Override
        public void onConfirmed(final String host, final int port, final DeviceSummary peer) {
            confirmed.add(host);
        }

        @Override
        public void onFailed(final String reason) {
            failures.add(reason);
        }
    }
}
