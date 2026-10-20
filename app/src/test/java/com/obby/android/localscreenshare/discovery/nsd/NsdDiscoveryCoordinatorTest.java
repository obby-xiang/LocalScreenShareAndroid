package com.obby.android.localscreenshare.discovery.nsd;

import com.obby.android.localscreenshare.support.BackoffPolicy;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NsdDiscoveryCoordinatorTest {

    private static final String CURRENT = "_lss._tcp";

    private static final String LEGACY = "_localscreenshare._udp";

    private FakeDiscovery mDiscovery;

    private FakeScheduler mScheduler;

    private NsdDiscoveryCoordinator mCoordinator;

    @Before
    public void setUp() {
        mDiscovery = new FakeDiscovery();
        mScheduler = new FakeScheduler();
        mCoordinator = new NsdDiscoveryCoordinator(mDiscovery, mScheduler,
            Arrays.asList(CURRENT, LEGACY), BackoffPolicy::defaultsWithoutJitter);
    }

    @Test
    public void startAllStartsEveryServiceType() {
        mCoordinator.startAll();

        assertEquals(Arrays.asList("start:" + CURRENT, "start:" + LEGACY), mDiscovery.events);
        assertTrue(mCoordinator.isRunning(CURRENT));
        assertTrue(mCoordinator.isRunning(LEGACY));
        assertTrue(mCoordinator.isActive());
    }

    @Test
    public void startAllIsIdempotentWhileActive() {
        mCoordinator.startAll();
        mCoordinator.startAll();

        assertEquals(2, mDiscovery.events.size());
    }

    @Test
    public void failedStartRetriesOnlyThatType() {
        mCoordinator.startAll();
        mDiscovery.events.clear();

        long delayMs = mCoordinator.onStartFailed(CURRENT);

        assertEquals(1_000L, delayMs);
        assertFalse(mCoordinator.isRunning(CURRENT));
        assertTrue(mCoordinator.isRunning(LEGACY));
        assertEquals(1, mCoordinator.pendingRetryCount());

        mScheduler.runAll();

        assertEquals(Collections.singletonList("start:" + CURRENT), mDiscovery.events);
        assertEquals(0, mCoordinator.pendingRetryCount());
    }

    @Test
    public void repeatedFailuresFollowTheBackoffSchedule() {
        mCoordinator.startAll();

        assertEquals(1_000L, mCoordinator.onStartFailed(CURRENT));
        mScheduler.runAll();
        assertEquals(2_000L, mCoordinator.onStartFailed(CURRENT));
        mScheduler.runAll();
        assertEquals(4_000L, mCoordinator.onStartFailed(CURRENT));
        mScheduler.runAll();
        assertEquals(8_000L, mCoordinator.onStartFailed(CURRENT));
    }

    @Test
    public void successClearsTheBackoff() {
        mCoordinator.startAll();
        mCoordinator.onStartFailed(CURRENT);

        mCoordinator.onDiscoveryStarted(CURRENT);
        long delayMs = mCoordinator.onStartFailed(CURRENT);

        assertEquals(1_000L, delayMs);
    }

    @Test
    public void networkChangeRestartsRunningTypesAfterTheDebounce() {
        mCoordinator.startAll();
        mCoordinator.onDiscoveryStarted(CURRENT);
        mCoordinator.onDiscoveryStarted(LEGACY);
        mDiscovery.events.clear();

        mCoordinator.onNetworkChanged();

        assertTrue(mCoordinator.isNetworkRestartPending());
        assertTrue(mDiscovery.events.isEmpty());
        assertEquals(NsdDiscoveryCoordinator.NETWORK_CHANGE_DEBOUNCE_MS, mScheduler.nextDelayMs());

        mScheduler.runAll();

        assertEquals(Arrays.asList("stop:" + CURRENT, "start:" + CURRENT, "stop:" + LEGACY, "start:" + LEGACY),
            mDiscovery.events);
        assertFalse(mCoordinator.isNetworkRestartPending());
    }

    @Test
    public void networkChangeBurstIsCoalescedIntoOneRestart() {
        mCoordinator.startAll();
        mCoordinator.onDiscoveryStarted(CURRENT);
        mCoordinator.onDiscoveryStarted(LEGACY);
        mDiscovery.events.clear();

        mCoordinator.onNetworkChanged();
        mCoordinator.onNetworkChanged();
        mCoordinator.onNetworkChanged();

        assertEquals(1, mScheduler.pendingCount());

        mScheduler.runAll();

        assertEquals(4, mDiscovery.events.size());
    }

    @Test
    public void networkChangeCancelsPendingRetry() {
        mCoordinator.startAll();
        mCoordinator.onStartFailed(CURRENT);
        assertEquals(1, mCoordinator.pendingRetryCount());
        mDiscovery.events.clear();

        mCoordinator.onNetworkChanged();
        mScheduler.runAll();

        assertEquals(0, mCoordinator.pendingRetryCount());
        assertEquals(Arrays.asList("start:" + CURRENT, "stop:" + LEGACY, "start:" + LEGACY), mDiscovery.events);
    }

    @Test
    public void networkChangeResetsTheBackoff() {
        mCoordinator.startAll();
        mCoordinator.onStartFailed(CURRENT);
        mScheduler.runAll();
        assertEquals(2_000L, mCoordinator.onStartFailed(CURRENT));
        mScheduler.runAll();

        mCoordinator.onNetworkChanged();
        mScheduler.runAll();

        assertEquals(1_000L, mCoordinator.onStartFailed(CURRENT));
    }

    @Test
    public void networkChangeIsIgnoredWhenInactive() {
        mCoordinator.onNetworkChanged();

        assertFalse(mCoordinator.isNetworkRestartPending());
        mScheduler.runAll();
        assertTrue(mDiscovery.events.isEmpty());
    }

    @Test
    public void stopAllStopsRunningTypesAndCancelsRetries() {
        mCoordinator.startAll();
        mCoordinator.onDiscoveryStarted(CURRENT);
        mCoordinator.onDiscoveryStarted(LEGACY);
        mCoordinator.onStartFailed(CURRENT);
        mDiscovery.events.clear();

        mCoordinator.stopAll();

        assertEquals(Collections.singletonList("stop:" + LEGACY), mDiscovery.events);
        assertEquals(0, mCoordinator.pendingRetryCount());
        assertFalse(mCoordinator.isActive());

        mScheduler.runAll();
        assertEquals(1, mDiscovery.events.size());
    }

    @Test
    public void unknownServiceTypeIsIgnored() {
        mCoordinator.startAll();

        assertEquals(-1L, mCoordinator.onStartFailed("_other._tcp"));
        assertFalse(mCoordinator.isRunning("_other._tcp"));
    }

    private static final class FakeDiscovery implements NsdDiscoveryCoordinator.Discovery {
        private final List<String> events = new ArrayList<>();

        @Override
        public void startDiscovery(String serviceType) {
            events.add("start:" + serviceType);
        }

        @Override
        public void stopDiscovery(String serviceType) {
            events.add("stop:" + serviceType);
        }
    }

    private static final class FakeScheduler implements NsdDiscoveryCoordinator.Scheduler {
        private final List<Task> tasks = new ArrayList<>();

        @Override
        public NsdDiscoveryCoordinator.Cancellable schedule(long delayMs, Runnable task) {
            Task scheduled = new Task(delayMs, task);
            tasks.add(scheduled);
            return scheduled;
        }

        int pendingCount() {
            int count = 0;
            for (Task task : tasks) {
                if (!task.cancelled) {
                    count++;
                }
            }
            return count;
        }

        long nextDelayMs() {
            for (Task task : tasks) {
                if (!task.cancelled) {
                    return task.delayMs;
                }
            }
            return -1L;
        }

        boolean runNext() {
            for (Task task : tasks) {
                if (!task.cancelled) {
                    tasks.remove(task);
                    task.runnable.run();
                    return true;
                }
            }
            return false;
        }

        void runAll() {
            while (runNext()) {
                // Keep running until no task is pending.
            }
        }
    }

    private static final class Task implements NsdDiscoveryCoordinator.Cancellable {
        private final long delayMs;

        private final Runnable runnable;

        private boolean cancelled;

        private Task(long delayMs, Runnable runnable) {
            this.delayMs = delayMs;
            this.runnable = runnable;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
