package com.obby.android.localscreenshare.discovery.scan;

import com.obby.android.localscreenshare.core.support.MutableClock;
import com.obby.android.localscreenshare.discovery.support.FakeProbe;
import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;

import org.junit.Before;
import org.junit.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AddressScannerTest {

    private FakeProbe mProbe;

    private MutableClock mClock;

    private AddressScanner mScanner;

    @Before
    public void setUp() {
        mProbe = FakeProbe.alwaysFailing();
        mClock = new MutableClock(0L);
        mScanner = new AddressScanner(mProbe, 10, 8_000L, mClock);
    }

    @Test
    public void countsProbesAndConfirmedHits() {
        mProbe = new FakeProbe((host, port) -> host.endsWith(".2") || host.endsWith(".5")
            ? confirmed(host, port) : ProbeResult.failed(host, port, "no_answer"));
        mScanner = new AddressScanner(mProbe, 10, 8_000L, mClock);

        final AddressScanner.ScanResult result = mScanner.scan(hosts("10.0.0.", 10), 8080, false);

        assertEquals(10, result.getScanned());
        assertEquals(2, result.getConfirmedCount());
        final java.util.Set<String> confirmedHosts = new java.util.HashSet<>();
        for (final AddressScanner.ScanHit hit : result.getConfirmed()) {
            confirmedHosts.add(hit.getHost());
            assertEquals(8080, hit.getPort());
        }
        assertTrue(confirmedHosts.contains("10.0.0.2"));
        assertTrue(confirmedHosts.contains("10.0.0.5"));
        assertFalse(result.isCancelled());
        assertFalse(result.isPartial());
    }

    @Test
    public void partialFlagIsPropagated() {
        final AddressScanner.ScanResult result = mScanner.scan(hosts("10.0.0.", 3), 8080, true);

        assertTrue(result.isPartial());
        assertEquals(3, result.getScanned());
    }

    @Test
    public void concurrencyNeverExceedsTheConfiguredBound() {
        mProbe = FakeProbe.confirmingAll("device");
        mProbe.setDelayMs(2L);
        mScanner = new AddressScanner(mProbe, 50, 8_000L, mClock);

        final AddressScanner.ScanResult result = mScanner.scan(hosts("10.0.1.", 200), 8080, false);

        assertEquals(200, result.getScanned());
        assertTrue("max concurrency was " + mProbe.maxConcurrent(), mProbe.maxConcurrent() <= 50);
        assertTrue("probes did not overlap", mProbe.maxConcurrent() > 1);
    }

    @Test
    public void cancelStopsFurtherProbes() {
        final AtomicReference<AddressScanner> scanner = new AtomicReference<>();
        mProbe = new FakeProbe((host, port) -> ProbeResult.failed(host, port, "no_answer"));
        mProbe.setOnProbe(() -> {
            final AddressScanner current = scanner.get();
            if (current != null) {
                current.cancel();
            }
        });
        scanner.set(new AddressScanner(mProbe, 2, 8_000L, mClock));

        final AddressScanner.ScanResult result = scanner.get().scan(hosts("10.0.2.", 100), 8080,
            false);

        assertTrue(result.isCancelled());
        assertTrue("scanned " + result.getScanned(), result.getScanned() < 100);
    }

    @Test
    public void aHitReturnedAfterCancelIsIgnored() {
        final AtomicReference<AddressScanner> scanner = new AtomicReference<>();
        mProbe = new FakeProbe(AddressScannerTest::confirmed);
        mProbe.setOnProbe(() -> {
            final AddressScanner current = scanner.get();
            if (current != null) {
                current.cancel();
            }
        });
        scanner.set(new AddressScanner(mProbe, 1, 8_000L, mClock));

        final AddressScanner.ScanResult result = scanner.get().scan(hosts("10.0.3.", 10), 8080,
            false);

        assertTrue(result.isCancelled());
        assertEquals(0, result.getConfirmedCount());
    }

    @Test
    public void budgetStopsDispatch() {
        final AtomicLong nowMs = new AtomicLong(0L);
        mProbe = new FakeProbe((host, port) -> ProbeResult.failed(host, port, "no_answer"));
        mProbe.setOnProbe(() -> nowMs.addAndGet(100L));
        final AddressScanner scanner = new AddressScanner(mProbe, 1, 1_000L, nowMs::get);

        final AddressScanner.ScanResult result = scanner.scan(hosts("10.0.4.", 100), 8080, false);

        assertEquals(10, result.getScanned());
        assertFalse(result.isCancelled());
    }

    @Test
    public void emptyCandidatesCompleteImmediately() {
        final AddressScanner.ScanResult result = mScanner.scan(new ArrayList<>(), 8080, true);

        assertEquals(0, result.getScanned());
        assertEquals(0, result.getConfirmedCount());
        assertTrue(result.isPartial());
        assertEquals(0, mProbe.callCount());
    }

    @Test
    public void aThrowingProbeIsAFailedProbeNotACrash() {
        mProbe = new FakeProbe((host, port) -> {
            throw new IllegalStateException("boom");
        });
        mScanner = new AddressScanner(mProbe, 4, 8_000L, mClock);

        final AddressScanner.ScanResult result = mScanner.scan(hosts("10.0.5.", 4), 8080, false);

        assertEquals(0, result.getScanned());
        assertEquals(0, result.getConfirmedCount());
    }

    private static ProbeResult confirmed(final String host, final int port) {
        return ProbeResult.confirmed(DeviceSummary.newBuilder()
            .setDeviceId("device-" + host)
            .setFingerprint("fingerprint")
            .build(), host, port);
    }

    private static List<Inet4Address> hosts(final String prefix, final int count) {
        final List<Inet4Address> hosts = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            hosts.add(ip(prefix + i));
        }
        return hosts;
    }

    private static Inet4Address ip(final String literal) {
        try {
            return (Inet4Address) InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(literal, e);
        }
    }
}
