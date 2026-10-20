package com.obby.android.localscreenshare.discovery.scan;

import com.obby.android.localscreenshare.core.support.MutableClock;
import com.obby.android.localscreenshare.discovery.support.FakeDelayedScheduler;
import com.obby.android.localscreenshare.discovery.support.FakeInterfaceEnumerator;
import com.obby.android.localscreenshare.discovery.support.FakeProbe;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScanCoordinatorTest {

    private FakeDelayedScheduler mScheduler;

    private FakeInterfaceEnumerator mEnumerator;

    private FakeProbe mProbe;

    private AddressScanner mScanner;

    private MutableClock mClock;

    private AtomicInteger mConfirmedCount;

    private RecordingListener mListener;

    private ScanCoordinator mCoordinator;

    @Before
    public void setUp() {
        mScheduler = new FakeDelayedScheduler();
        mEnumerator = new FakeInterfaceEnumerator(LocalInterface.ipv4("wlan0", "192.168.1.5", 24,
            LocalInterface.Transport.WIFI));
        mProbe = FakeProbe.alwaysFailing();
        mClock = new MutableClock(0L);
        mScanner = new AddressScanner(mProbe, 10, 8_000L, mClock);
        mConfirmedCount = new AtomicInteger();
        mListener = new RecordingListener();
        mCoordinator = new ScanCoordinator(mScheduler, mEnumerator, mScanner, Runnable::run,
            Runnable::run, mConfirmedCount::get, () -> 8080);
        mCoordinator.setListener(mListener);
    }

    @Test
    public void aConfirmedDeviceDuringGraceSkipsTheScan() {
        mConfirmedCount.set(1);

        mCoordinator.start(ScanCoordinator.Trigger.DISCOVERY_START);
        mScheduler.advance(ScanCoordinator.GRACE_MS);

        assertEquals(0, mCoordinator.runs());
        assertEquals(0, mProbe.callCount());
        assertEquals(ScanCoordinator.State.DONE, mCoordinator.state());
    }

    @Test
    public void zeroConfirmationsEscalateToScan() {
        mCoordinator.start(ScanCoordinator.Trigger.DISCOVERY_START);
        mScheduler.advance(ScanCoordinator.GRACE_MS);

        assertEquals(1, mCoordinator.runs());
        assertEquals(253, mProbe.callCount());
        assertEquals(ScanCoordinator.State.DONE, mCoordinator.state());
    }

    @Test
    public void scanStartsOnlyWhenTheGraceWindowElapsed() {
        mCoordinator.start(ScanCoordinator.Trigger.DISCOVERY_START);

        mScheduler.advance(ScanCoordinator.GRACE_MS - 1L);

        assertEquals(0, mCoordinator.runs());
        assertEquals(ScanCoordinator.State.GRACE, mCoordinator.state());
        assertEquals(0, mProbe.callCount());

        mScheduler.advance(1L);

        assertEquals(1, mCoordinator.runs());
        assertEquals(253, mProbe.callCount());
    }

    @Test
    public void repeatedTriggersCoalesceIntoOneRun() {
        mCoordinator.start(ScanCoordinator.Trigger.DISCOVERY_START);
        mCoordinator.start(ScanCoordinator.Trigger.MANUAL_REFRESH);
        mCoordinator.start(ScanCoordinator.Trigger.EMPTY_LIST);

        mScheduler.advance(ScanCoordinator.GRACE_MS);

        assertEquals(1, mCoordinator.runs());
        assertEquals(253, mProbe.callCount());
    }

    @Test
    public void aNetworkChangeRestartsTheGraceWindow() {
        mCoordinator.start(ScanCoordinator.Trigger.DISCOVERY_START);
        mScheduler.advance(500L);

        mCoordinator.start(ScanCoordinator.Trigger.NETWORK_CHANGED);
        mScheduler.advance(ScanCoordinator.GRACE_MS - 1L);

        assertEquals(0, mCoordinator.runs());

        mScheduler.advance(1L);

        assertEquals(1, mCoordinator.runs());
    }

    @Test
    public void cancelDuringGracePreventsTheScan() {
        mCoordinator.start(ScanCoordinator.Trigger.DISCOVERY_START);
        mCoordinator.cancel();

        mScheduler.advance(30_000L);

        assertEquals(0, mCoordinator.runs());
        assertEquals(ScanCoordinator.State.IDLE, mCoordinator.state());
        assertEquals(0, mProbe.callCount());
    }

    @Test
    public void answeredPeersReachTheListener() {
        mEnumerator.set(LocalInterface.ipv4("wlan0", "10.0.0.5", 30,
            LocalInterface.Transport.WIFI));
        mProbe = new FakeProbe((host, port) -> ProbeResult.confirmed(
            com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary.newBuilder()
                .setDeviceId("device-id")
                .build(), host, port));
        mScanner = new AddressScanner(mProbe, 4, 8_000L, mClock);
        mCoordinator = new ScanCoordinator(mScheduler, mEnumerator, mScanner, Runnable::run,
            Runnable::run, mConfirmedCount::get, () -> 8080);
        mCoordinator.setListener(mListener);

        mCoordinator.start(ScanCoordinator.Trigger.DISCOVERY_START);
        mScheduler.advance(ScanCoordinator.GRACE_MS);

        assertEquals(1, mProbe.callCount());
        assertEquals(1, mListener.results.size());
        final AddressScanner.ScanResult result = mListener.results.get(0);
        assertEquals(1, result.getConfirmedCount());
        assertEquals("10.0.0.6", result.getConfirmed().get(0).getHost());
    }

    @Test
    public void anEmptyInterfaceListCompletesWithoutScanning() {
        mEnumerator.set();

        mCoordinator.start(ScanCoordinator.Trigger.DISCOVERY_START);
        mScheduler.advance(ScanCoordinator.GRACE_MS);

        assertEquals(1, mCoordinator.runs());
        assertEquals(0, mProbe.callCount());
        assertEquals(ScanCoordinator.State.DONE, mCoordinator.state());
        assertEquals(1, mListener.results.size());
        assertEquals(0, mListener.results.get(0).getScanned());
    }

    @Test
    public void aNetworkChangeAfterADoneRunStartsAnotherRun() {
        mCoordinator.start(ScanCoordinator.Trigger.DISCOVERY_START);
        mScheduler.advance(ScanCoordinator.GRACE_MS);
        assertEquals(1, mCoordinator.runs());

        mCoordinator.start(ScanCoordinator.Trigger.NETWORK_CHANGED);
        mScheduler.advance(ScanCoordinator.GRACE_MS);

        assertEquals(2, mCoordinator.runs());
    }

    @Test
    public void statesAreReportedInOrder() {
        mCoordinator.start(ScanCoordinator.Trigger.DISCOVERY_START);
        mScheduler.advance(ScanCoordinator.GRACE_MS);

        assertFalse(mListener.states.isEmpty());
        assertEquals(ScanCoordinator.State.GRACE, mListener.states.get(0));
        assertEquals(ScanCoordinator.State.SCANNING, mListener.states.get(1));
        assertEquals(ScanCoordinator.State.DONE, mListener.states.get(mListener.states.size() - 1));
    }

    private static final class RecordingListener implements ScanCoordinator.Listener {
        private final List<ScanCoordinator.State> states = new ArrayList<>();
        private final List<AddressScanner.ScanResult> results = new ArrayList<>();

        @Override
        public void onStateChanged(final ScanCoordinator.State state) {
            states.add(state);
        }

        @Override
        public void onScanCompleted(final AddressScanner.ScanResult result) {
            results.add(result);
        }
    }
}
