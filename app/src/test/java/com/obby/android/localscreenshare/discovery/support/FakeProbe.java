package com.obby.android.localscreenshare.discovery.support;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.discovery.scan.AddressProbe;
import com.obby.android.localscreenshare.discovery.scan.ProbeResult;
import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link AddressProbe} that records calls, tracks concurrency and answers from a responder.
 *
 * <p>Nothing sleeps unless a test asks for it, so the scanner and coordinator state machines run
 * deterministically. A hook runs at the start of every probe, which is how a test cancels a scan
 * from inside it or advances a clock between probes.</p>
 */
public final class FakeProbe implements AddressProbe {

    /**
     * Decides the answer of one probe.
     */
    @FunctionalInterface
    public interface Responder {
        /**
         * Returns the result for one address.
         */
        @NonNull
        ProbeResult respond(@NonNull String host, int port);
    }

    /**
     * One recorded probe.
     */
    public static final class Call {
        @NonNull
        public final String host;

        public final int port;

        public final long timeoutMs;

        private Call(@NonNull final String host, final int port, final long timeoutMs) {
            this.host = host;
            this.port = port;
            this.timeoutMs = timeoutMs;
        }
    }

    @NonNull
    private final Responder mResponder;

    @NonNull
    private final List<Call> mCalls = Collections.synchronizedList(new ArrayList<>());

    private final AtomicInteger mActive = new AtomicInteger();

    private final AtomicInteger mMaxActive = new AtomicInteger();

    private volatile long mDelayMs;

    @Nullable
    private volatile Runnable mOnProbe;

    /**
     * Creates a probe that answers with {@code responder}.
     */
    public FakeProbe(@NonNull final Responder responder) {
        mResponder = responder;
    }

    /**
     * Creates a probe whose every attempt fails.
     */
    @NonNull
    public static FakeProbe alwaysFailing() {
        return new FakeProbe((host, port) -> ProbeResult.failed(host, port, "unreachable"));
    }

    /**
     * Creates a probe that confirms every address with the given identity.
     */
    @NonNull
    public static FakeProbe confirmingAll(@NonNull final String deviceId) {
        return new FakeProbe((host, port) -> ProbeResult.confirmed(DeviceSummary.newBuilder()
            .setDeviceId(deviceId)
            .setFingerprint("fingerprint")
            .build(), host, port));
    }

    /**
     * Sets a sleep applied to every probe, used to observe concurrency.
     */
    public void setDelayMs(final long delayMs) {
        mDelayMs = delayMs;
    }

    /**
     * Sets a hook that runs at the start of every probe.
     */
    public void setOnProbe(@Nullable final Runnable onProbe) {
        mOnProbe = onProbe;
    }

    @NonNull
    @Override
    public ProbeResult probe(@NonNull final String host, final int port, final long timeoutMs) {
        final int active = mActive.incrementAndGet();
        mMaxActive.accumulateAndGet(active, Math::max);
        mCalls.add(new Call(host, port, timeoutMs));
        try {
            final Runnable hook = mOnProbe;
            if (hook != null) {
                hook.run();
            }
            final long delayMs = mDelayMs;
            if (delayMs > 0L) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return mResponder.respond(host, port);
        } finally {
            mActive.decrementAndGet();
        }
    }

    /**
     * Returns the recorded calls in completion order.
     */
    @NonNull
    public List<Call> calls() {
        synchronized (mCalls) {
            return new ArrayList<>(mCalls);
        }
    }

    /**
     * Returns the number of probes issued.
     */
    public int callCount() {
        return mCalls.size();
    }

    /**
     * Returns the highest number of probes that overlapped.
     */
    public int maxConcurrent() {
        return mMaxActive.get();
    }

    /**
     * Returns the probed hosts in call order.
     */
    @NonNull
    public List<String> hosts() {
        final List<String> hosts = new ArrayList<>();
        for (final Call call : calls()) {
            hosts.add(call.host);
        }
        return hosts;
    }
}
