package com.obby.android.localscreenshare.discovery.udp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.support.Constants;
import com.obby.android.localscreenshare.support.DelayedScheduler;

import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Server-side announcement state machine: startup burst, heartbeat and goodbye.
 *
 * <p>A session starts with three announcements after 100, 500 and 2000 ms, so a peer that is
 * already listening sees the device quickly and a peer that is still starting up is not required
 * to have heard the first datagram. After the burst the announcer sends one heartbeat per period,
 * 5 seconds while a capture session is live and 15 seconds while it is idle. Stopping sends three
 * goodbyes at 0, 100 and 300 ms with {@code announces=false}, then closes the sender.</p>
 *
 * <p>Every datagram carries a random per-session {@code boot} and a monotonically increasing
 * {@code seq}, so receivers can discard duplicates and reordering without a shared clock. The
 * content of one datagram is taken from a mutable template each time it is sent: a name or a
 * secure flag changed by the user is applied on the next heartbeat without restarting the
 * session.</p>
 *
 * <p><b>Threading.</b> All entry points are synchronized and the sender is called while holding
 * the monitor, which serializes sends even when the scheduler runs on a different thread. {@link
 * #stop()} sends the first goodbye synchronously and schedules the remaining two, so a caller that
 * stops the service does not block on the scheduler.</p>
 */
public final class MulticastAnnouncer {

    /** Sends and closes the underlying transport. */
    public interface Sender {
        /** Sends one encoded announcement. */
        void send(@NonNull byte[] payload);

        /** Releases the transport after the goodbye burst. */
        void close();
    }

    private enum Phase {
        IDLE,
        BURST,
        HEARTBEAT,
        GOODBYE
    }

    @NonNull
    private final Sender mSender;

    @NonNull
    private final DelayedScheduler mScheduler;

    @NonNull
    private final Supplier<Announcement> mTemplateSupplier;

    @NonNull
    private final Random mRandom;

    private boolean mRunning;

    private boolean mClosed;

    private boolean mLive = true;

    private final long mHeartbeatActiveMs;

    private final long mHeartbeatIdleMs;

    @NonNull
    private Phase mPhase = Phase.IDLE;

    @Nullable
    private String mBoot;

    private long mSeq;

    private int mBurstIndex;

    @Nullable
    private DelayedScheduler.Cancellable mPending;

    /**
     * Creates an announcer.
     *
     * @param sender transport sender
     * @param scheduler scheduler that drives the burst, heartbeat and goodbye
     * @param templateSupplier current announcement content, without boot and seq; a null value
     *     skips the send without stopping the schedule, which covers a temporarily unavailable
     *     device identity
     */
    public MulticastAnnouncer(@NonNull final Sender sender, @NonNull final DelayedScheduler scheduler,
        @NonNull final Supplier<Announcement> templateSupplier) {
        this(sender, scheduler, templateSupplier, new Random());
    }

    /** Creates an announcer with an explicit random source, so tests get a fixed boot. */
    public MulticastAnnouncer(@NonNull final Sender sender, @NonNull final DelayedScheduler scheduler,
        @NonNull final Supplier<Announcement> templateSupplier, @NonNull final Random random) {
        this(sender, scheduler, templateSupplier, random, Constants.MULTICAST_HEARTBEAT_ACTIVE_MS,
            Constants.MULTICAST_HEARTBEAT_IDLE_MS);
    }

    /**
     * Creates an announcer with explicit heartbeat periods.
     *
     * <p>The periods come from the discovery refresh preset; the startup burst and the goodbye
     * delays are fixed because they are part of the channel's behaviour, not of the cadence the
     * user chose.</p>
     *
     * @param heartbeatActiveMs period while a capture session is live
     * @param heartbeatIdleMs period while the server is idle
     */
    public MulticastAnnouncer(@NonNull final Sender sender, @NonNull final DelayedScheduler scheduler,
        @NonNull final Supplier<Announcement> templateSupplier, @NonNull final Random random,
        final long heartbeatActiveMs, final long heartbeatIdleMs) {
        mSender = Objects.requireNonNull(sender, "sender");
        mScheduler = Objects.requireNonNull(scheduler, "scheduler");
        mTemplateSupplier = Objects.requireNonNull(templateSupplier, "templateSupplier");
        mRandom = Objects.requireNonNull(random, "random");
        mHeartbeatActiveMs = heartbeatActiveMs > 0L
            ? heartbeatActiveMs : Constants.MULTICAST_HEARTBEAT_ACTIVE_MS;
        mHeartbeatIdleMs = heartbeatIdleMs > 0L
            ? heartbeatIdleMs : Constants.MULTICAST_HEARTBEAT_IDLE_MS;
    }

    /** Starts the startup burst; a second call while running is ignored. */
    public synchronized void start() {
        if (mRunning || mClosed) {
            return;
        }
        mRunning = true;
        mBoot = String.format(Locale.ROOT, "%016x", mRandom.nextLong());
        mSeq = 0L;
        mBurstIndex = 0;
        scheduleNextBurst();
    }

    /**
     * Switches the heartbeat period between live and idle.
     *
     * <p>While a heartbeat is pending it is rescheduled from now with the new period, so the
     * transition takes effect without waiting out the old interval; during the startup burst and
     * the goodbye the change only affects the next heartbeat.</p>
     */
    public synchronized void setLive(final boolean live) {
        if (mLive == live) {
            return;
        }
        mLive = live;
        if (mRunning && mPhase == Phase.HEARTBEAT) {
            cancelPending();
            scheduleHeartbeat();
        }
    }

    /**
     * Sends the goodbye burst and closes the sender.
     *
     * <p>The first goodbye is sent synchronously so a stopping service announces its departure
     * even if the process is torn down immediately afterwards; the remaining two follow after 100
     * and 300 ms. A stop without a preceding start does nothing.</p>
     */
    public synchronized void stop() {
        if (!mRunning || mClosed) {
            return;
        }
        mRunning = false;
        cancelPending();
        mPhase = Phase.GOODBYE;
        send(true);

        mPending = mScheduler.schedule(Constants.MULTICAST_GOODBYE_DELAYS_MS[1], () -> {
            synchronized (MulticastAnnouncer.this) {
                if (mClosed) {
                    return;
                }
                send(true);
                mPending = mScheduler.schedule(
                    Constants.MULTICAST_GOODBYE_DELAYS_MS[2] - Constants.MULTICAST_GOODBYE_DELAYS_MS[1],
                    () -> {
                        synchronized (MulticastAnnouncer.this) {
                            if (mClosed) {
                                return;
                            }
                            send(true);
                            closeSender();
                        }
                    });
            }
        });
    }

    /** True while the startup burst or the heartbeat is running. */
    public synchronized boolean isRunning() {
        return mRunning;
    }

    /** Returns the session boot, or null before {@link #start()}. */
    @Nullable
    public synchronized String boot() {
        return mBoot;
    }

    private void scheduleNextBurst() {
        if (!mRunning) {
            return;
        }
        if (mBurstIndex >= Constants.MULTICAST_BURST_DELAYS_MS.length) {
            scheduleHeartbeat();
            return;
        }
        // The table holds absolute offsets from the start, so each delay is the gap to the
        // previous one: 100 ms, then 400 ms (t=500), then 1500 ms (t=2000).
        final long offsetMs = Constants.MULTICAST_BURST_DELAYS_MS[mBurstIndex];
        final long previousOffsetMs = mBurstIndex == 0 ? 0L : Constants.MULTICAST_BURST_DELAYS_MS[mBurstIndex - 1];
        mBurstIndex++;
        mPhase = Phase.BURST;
        mPending = mScheduler.schedule(offsetMs - previousOffsetMs, this::onBurst);
    }

    private synchronized void onBurst() {
        if (!mRunning) {
            return;
        }
        send(false);
        scheduleNextBurst();
    }

    private void scheduleHeartbeat() {
        if (!mRunning) {
            return;
        }
        mPhase = Phase.HEARTBEAT;
        mPending = mScheduler.schedule(currentHeartbeatIntervalMs(), this::onHeartbeat);
    }

    private synchronized void onHeartbeat() {
        if (!mRunning) {
            return;
        }
        send(false);
        scheduleHeartbeat();
    }

    private long currentHeartbeatIntervalMs() {
        return mLive ? mHeartbeatActiveMs : mHeartbeatIdleMs;
    }

    private void cancelPending() {
        if (mPending != null) {
            mPending.cancel();
            mPending = null;
        }
    }

    private void closeSender() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        mPhase = Phase.IDLE;
        mPending = null;
        mSender.close();
    }

    private void send(final boolean goodbye) {
        final Announcement template = mTemplateSupplier.get();
        if (template == null) {
            return;
        }
        final int ttlSeconds = (int) (currentHeartbeatIntervalMs() / 1_000L);
        final Announcement announcement = template.toBuilder()
            .boot(mBoot)
            .seq(++mSeq)
            .ttlSeconds(ttlSeconds)
            .announces(!goodbye)
            .build();
        try {
            mSender.send(AnnouncementCodec.encode(announcement));
        } catch (IllegalArgumentException e) {
            // The template produced a payload over the wire limit; skipping one datagram keeps
            // the schedule alive and the next heartbeat retries.
        }
    }
}
