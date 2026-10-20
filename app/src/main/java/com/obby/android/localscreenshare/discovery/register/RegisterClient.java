package com.obby.android.localscreenshare.discovery.register;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.discovery.model.DeviceAddress;
import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterRequest;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterResponse;
import com.obby.android.localscreenshare.support.BackoffPolicy;
import com.obby.android.localscreenshare.support.DelayedScheduler;
import com.obby.android.localscreenshare.transport.ScreenStreamProtoMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Executor;

/**
 * Client side of the Register handshake: deadline, retries and per-address deduplication.
 *
 * <p>One call has a {@link RegisterConstants#REGISTER_TIMEOUT_MS 500 ms} deadline and at most two retries,
 * delayed by 200 ms and 500 ms respectively with jitter so many devices that heard the same
 * announcement do not retry in lockstep. A rejection is terminal: retrying an answer that already
 * said no only adds load. Only timeouts and transport failures are retried.</p>
 *
 * <p>At most one call per address is in flight. A duplicate request for the same address is
 * dropped and reported by returning false, which is how the announcement path collapses the three
 * startup datagrams into one handshake. Success is decided by
 * {@link RegisterProtoMapper#rejectionReason(RegisterResponse)}: accepted, a compatible protocol
 * version and a usable device id.</p>
 *
 * <p>All state is synchronized; scheduling and callbacks may run on any thread. Callbacks are
 * marshaled to the injected executor, which is the main-thread executor in production, so callers
 * can write the device store from inside them.</p>
 */
public final class RegisterClient {

    /** Receives the outcome of one registration on the callback executor. */
    public interface Callback {
        /** Called with the accepted response. */
        void onSuccess(@NonNull RegisterResponse response);

        /** Called with a short reason when the call failed or was rejected. */
        void onFailure(@NonNull String reason);
    }

    @NonNull
    private final RegisterStub mStub;

    @NonNull
    private final DelayedScheduler mScheduler;

    @NonNull
    private final Executor mCallbackExecutor;

    @NonNull
    private final BackoffPolicy.Jitter mRetryJitter;

    @NonNull
    private final Map<String, Pending> mPending = new HashMap<>();

    // Deadline of one call; the discovery refresh preset changes it between startups.
    private volatile long mTimeoutMs = RegisterConstants.REGISTER_TIMEOUT_MS;

    /**
     * Creates a client with the default retry jitter.
     *
     * @param stub transport of one call
     * @param scheduler scheduler used for deadlines and retries; owned by the caller
     * @param callbackExecutor executor that receives every callback
     */
    public RegisterClient(@NonNull final RegisterStub stub, @NonNull final DelayedScheduler scheduler,
        @NonNull final Executor callbackExecutor) {
        this(stub, scheduler, callbackExecutor,
            BackoffPolicy.randomJitter(0.2d, new Random()));
    }

    /** Creates a client with an explicit retry jitter; tests pass {@link BackoffPolicy#NO_JITTER}. */
    public RegisterClient(@NonNull final RegisterStub stub, @NonNull final DelayedScheduler scheduler,
        @NonNull final Executor callbackExecutor, @NonNull final BackoffPolicy.Jitter retryJitter) {
        mStub = Objects.requireNonNull(stub, "stub");
        mScheduler = Objects.requireNonNull(scheduler, "scheduler");
        mCallbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        mRetryJitter = Objects.requireNonNull(retryJitter, "retryJitter");
    }

    /**
     * Starts a registration.
     *
     * @param address endpoint to call
     * @param announces whether this call may trigger one reverse Register at the server
     * @param self this device's discovery summary
     * @param callback outcome callback
     * @return false when a call for the same address is already in flight
     */
    public boolean register(@NonNull final DeviceAddress address, final boolean announces,
        @NonNull final DeviceSummary self, @NonNull final Callback callback) {
        final String key = key(address);
        final Pending pending = new Pending(key, address, announces, self, callback);
        synchronized (mPending) {
            if (mPending.containsKey(key)) {
                return false;
            }
            mPending.put(key, pending);
        }
        attempt(pending);
        return true;
    }

    /** Returns the number of calls currently in flight. */
    public int pendingCount() {
        synchronized (mPending) {
            return mPending.size();
        }
    }

    /**
     * Sets the deadline applied to calls started afterwards.
     *
     * <p>The discovery refresh preset owns this value; an in-flight call keeps the deadline it
     * started with. A non-positive value restores the default.</p>
     */
    public void setTimeoutMs(final long timeoutMs) {
        mTimeoutMs = timeoutMs > 0L ? timeoutMs : RegisterConstants.REGISTER_TIMEOUT_MS;
    }

    /** Cancels every in-flight call and suppresses their callbacks. */
    public void cancelAll() {
        final List<Pending> pending;
        synchronized (mPending) {
            pending = new ArrayList<>(mPending.values());
            mPending.clear();
        }
        for (final Pending call : pending) {
            finish(call);
        }
    }

    private void attempt(@NonNull final Pending pending) {
        final int attemptId;
        final long timeoutMs = mTimeoutMs;
        synchronized (pending) {
            if (pending.finished) {
                return;
            }
            attemptId = ++pending.attemptId;
            pending.active = true;
            pending.timeout = mScheduler.schedule(timeoutMs,
                () -> failAttempt(pending, attemptId, "timeout"));
        }

        final RegisterRequest request = RegisterRequest.newBuilder()
            .setProtocolVersion(ScreenStreamProtoMapper.PROTOCOL_VERSION)
            .setAnnounces(pending.announces)
            .setSelf(pending.self)
            .build();
        try {
            mStub.register(pending.address, request, timeoutMs,
                new RegisterStub.Callback() {
                    @Override
                    public void onResponse(@NonNull final RegisterResponse response) {
                        onAttemptResponse(pending, attemptId, response);
                    }

                    @Override
                    public void onFailure(@NonNull final String reason) {
                        failAttempt(pending, attemptId, reason);
                    }
                });
        } catch (RuntimeException e) {
            failAttempt(pending, attemptId, "call_failed");
        }
    }

    private void onAttemptResponse(@NonNull final Pending pending, final int attemptId,
        @NonNull final RegisterResponse response) {
        synchronized (pending) {
            if (pending.finished || !pending.active || pending.attemptId != attemptId) {
                return;
            }
            pending.active = false;
            cancelTimers(pending);
        }

        final String rejection = RegisterProtoMapper.rejectionReason(response);
        finish(pending);
        if (rejection == null) {
            deliver(() -> pending.callback.onSuccess(response));
        } else {
            // A rejection is definitive: the remote answered, so retrying cannot change it.
            deliver(() -> pending.callback.onFailure(rejection));
        }
    }

    private void failAttempt(@NonNull final Pending pending, final int attemptId,
        @NonNull final String reason) {
        synchronized (pending) {
            if (pending.finished || !pending.active || pending.attemptId != attemptId) {
                return;
            }
            pending.active = false;
            cancelTimers(pending);
            if (pending.retries < RegisterConstants.REGISTER_RETRY_DELAYS_MS.length) {
                final long delayMs = mRetryJitter.apply(
                    RegisterConstants.REGISTER_RETRY_DELAYS_MS[pending.retries]);
                pending.retries++;
                pending.retry = mScheduler.schedule(delayMs, () -> attempt(pending));
                return;
            }
        }

        finish(pending);
        deliver(() -> pending.callback.onFailure(reason));
    }

    private void finish(@NonNull final Pending pending) {
        synchronized (pending) {
            pending.finished = true;
            pending.active = false;
            cancelTimers(pending);
        }
        synchronized (mPending) {
            if (mPending.get(pending.key) == pending) {
                mPending.remove(pending.key);
            }
        }
    }

    private void cancelTimers(@NonNull final Pending pending) {
        if (pending.timeout != null) {
            pending.timeout.cancel();
            pending.timeout = null;
        }
        if (pending.retry != null) {
            pending.retry.cancel();
            pending.retry = null;
        }
    }

    private void deliver(@NonNull final Runnable callback) {
        mCallbackExecutor.execute(() -> {
            try {
                callback.run();
            } catch (RuntimeException e) {
                // A throwing consumer must not take the scheduler thread down.
            }
        });
    }

    @NonNull
    private static String key(@NonNull final DeviceAddress address) {
        return address.getHost() + "|" + address.getPort();
    }

    /** Mutable state of one in-flight registration. */
    private static final class Pending {
        @NonNull
        final String key;

        @NonNull
        final DeviceAddress address;

        final boolean announces;

        @NonNull
        final DeviceSummary self;

        @NonNull
        final Callback callback;

        int attemptId;

        int retries;

        boolean active;

        boolean finished;

        DelayedScheduler.Cancellable timeout;

        DelayedScheduler.Cancellable retry;

        private Pending(@NonNull final String key, @NonNull final DeviceAddress address,
            final boolean announces, @NonNull final DeviceSummary self, @NonNull final Callback callback) {
            this.key = key;
            this.address = address;
            this.announces = announces;
            this.self = self;
            this.callback = callback;
        }
    }
}
