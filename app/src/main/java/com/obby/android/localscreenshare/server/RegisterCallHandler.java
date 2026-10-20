package com.obby.android.localscreenshare.server;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.core.stream.TimeSource;
import com.obby.android.localscreenshare.discovery.model.DeviceAddress;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.register.RegisterClient;
import com.obby.android.localscreenshare.discovery.register.RegisterConstants;
import com.obby.android.localscreenshare.discovery.register.RegisterProtoMapper;
import com.obby.android.localscreenshare.discovery.store.DeviceStore;
import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterRequest;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterResponse;
import com.obby.android.localscreenshare.support.DelayedScheduler;
import com.obby.android.localscreenshare.transport.ScreenStreamProtoMapper;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Server side of the Register handshake: validation, rate limiting, mutual learning and loop
 * prevention.
 *
 * <p>A request is counted against the rate limit before anything else, then validated. A valid
 * request whose caller carries an identity and a fingerprint is written to the local device store
 * as a confirmed address and answered with this device's summary. The caller's summary is also
 * how a server learns about clients that never announced themselves.</p>
 *
 * <p><b>Loop prevention.</b> {@code announces=true} means the caller announced itself over
 * multicast. The server may answer with at most one {@code announces=false} Register back, sent
 * after a random delay of up to {@value Constants#REGISTER_REVERSE_MAX_DELAY_MS} ms and subject to
 * its own quota; the reverse call never triggers another reverse call because it carries
 * {@code announces=false}. Every Register chain is therefore at most one hop long.</p>
 *
 * <p><b>Threading.</b> {@link #handle(RegisterRequest, String, Replier)} is called on a gRPC
 * thread. Rate limiting is synchronized; store writes are marshaled to the main-thread executor,
 * because the store is main-thread confined; the response is written on the calling thread. The
 * reverse call is scheduled on the delayed scheduler.</p>
 */
public final class RegisterCallHandler {

    /** The caller did not send a device id or a fingerprint. */
    public static final String REASON_MISSING_IDENTITY = "missing_identity";

    /** The caller's identity fields are malformed. */
    public static final String REASON_INVALID_IDENTITY = "invalid_identity";

    /** The request is structurally invalid. */
    public static final String REASON_INVALID_REQUEST = "invalid_request";

    /** The caller speaks a newer protocol version than this server. */
    public static final String REASON_UNSUPPORTED_VERSION = "unsupported_version";

    /** The caller exceeded its request quota. */
    public static final String REASON_RATE_LIMITED = "rate_limited";

    /** This server is stopping and cannot answer discovery requests. */
    public static final String REASON_UNAVAILABLE = "unavailable";

    /** The caller is not allowed to see or reach this device under its visibility preset. */
    public static final String REASON_NOT_VISIBLE = "not_visible";

    /** Length of the device id derived from the identity key. */
    public static final int DEVICE_ID_LENGTH = 26;

    /** Maximum length of a fingerprint field. */
    public static final int MAX_FINGERPRINT_LENGTH = 64;

    /** Maximum length of a name field. */
    public static final int MAX_NAME_LENGTH = 64;

    /** Maximum number of addresses accepted in one summary. */
    public static final int MAX_ADDRESSES = 4;

    private static final long RATE_WINDOW_MS = 60_000L;

    /** Receives the response of one request. */
    @FunctionalInterface
    public interface Replier {
        /** Sends the response; called on the thread that handled the request. */
        void respond(@NonNull RegisterResponse response);
    }

    /** Issues one reverse Register; implemented by {@link RegisterClient}. */
    @FunctionalInterface
    public interface ReverseRegistrar {
        /** Starts one call that must not trigger another reverse call. */
        void register(@NonNull DeviceAddress address, boolean announces, @NonNull DeviceSummary self,
            @NonNull RegisterClient.Callback callback);
    }

    @NonNull
    private final Supplier<DeviceSummary> mSelfSupplier;

    @Nullable
    private final DeviceStore mDeviceStore;

    @NonNull
    private final Executor mMainExecutor;

    @NonNull
    private final TimeSource mTimeSource;

    @Nullable
    private final ReverseRegistrar mReverseRegistrar;

    @Nullable
    private final DelayedScheduler mScheduler;

    @Nullable
    private final Predicate<String> mCallerFilter;

    @NonNull
    private final Random mRandom;

    @NonNull
    private final DiscoveryChannel mChannel;

    @NonNull
    private final RateLimiter mGlobalLimiter =
        new RateLimiter(RegisterConstants.REGISTER_REQUESTS_PER_MINUTE, RATE_WINDOW_MS);

    @NonNull
    private final RateLimiter mAddressLimiter =
        new RateLimiter(RegisterConstants.REGISTER_REQUESTS_PER_ADDRESS_PER_MINUTE, RATE_WINDOW_MS);

    @NonNull
    private final RateLimiter mReverseLimiter =
        new RateLimiter(RegisterConstants.REGISTER_REVERSE_PER_MINUTE, RATE_WINDOW_MS);

    /**
     * Creates a handler without a reverse call; used by tests and by a server that does not
     * announce itself.
     */
    public RegisterCallHandler(@NonNull final Supplier<DeviceSummary> selfSupplier,
        @Nullable final DeviceStore deviceStore, @NonNull final Executor mainExecutor,
        @NonNull final TimeSource timeSource) {
        this(selfSupplier, deviceStore, mainExecutor, timeSource, null, null, new Random(),
            DiscoveryChannel.MULTICAST);
    }

    /**
     * Creates a handler.
     *
     * @param selfSupplier this device's summary, or null while the server cannot answer
     * @param deviceStore store that learns the caller; null disables mutual learning
     * @param mainExecutor executor of the store's thread
     * @param timeSource clock for rate limiting
     * @param reverseRegistrar optional reverse Register
     * @param scheduler scheduler of the reverse delay; required when {@code reverseRegistrar} is set
     * @param random source of the reverse delay
     * @param channel channel the caller's address is stored under
     */
    public RegisterCallHandler(@NonNull final Supplier<DeviceSummary> selfSupplier,
        @Nullable final DeviceStore deviceStore, @NonNull final Executor mainExecutor,
        @NonNull final TimeSource timeSource, @Nullable final ReverseRegistrar reverseRegistrar,
        @Nullable final DelayedScheduler scheduler, @NonNull final Random random,
        @NonNull final DiscoveryChannel channel) {
        this(selfSupplier, deviceStore, mainExecutor, timeSource, reverseRegistrar, scheduler, random,
            channel, null);
    }

    /**
     * Creates a handler with a caller filter.
     *
     * <p>{@code callerFilter} implements the local visibility preset: it is consulted with the
     * caller's device id after validation and before the caller is learned. A rejected caller gets
     * {@link #REASON_NOT_VISIBLE} and is not written to the store, so a hidden device never shows
     * up in a peer's list. A null filter accepts every caller that passes validation.</p>
     *
     * @param selfSupplier this device's summary, or null while the server cannot answer
     * @param deviceStore store that learns the caller; null disables mutual learning
     * @param mainExecutor executor of the store's thread
     * @param timeSource clock for rate limiting and visibility expiry
     * @param reverseRegistrar optional reverse Register
     * @param scheduler scheduler of the reverse delay; required when {@code reverseRegistrar} is set
     * @param random source of the reverse delay
     * @param channel channel the caller's address is stored under
     * @param callerFilter visibility gate, or null to accept every validated caller
     */
    public RegisterCallHandler(@NonNull final Supplier<DeviceSummary> selfSupplier,
        @Nullable final DeviceStore deviceStore, @NonNull final Executor mainExecutor,
        @NonNull final TimeSource timeSource, @Nullable final ReverseRegistrar reverseRegistrar,
        @Nullable final DelayedScheduler scheduler, @NonNull final Random random,
        @NonNull final DiscoveryChannel channel, @Nullable final Predicate<String> callerFilter) {
        mSelfSupplier = Objects.requireNonNull(selfSupplier, "selfSupplier");
        mDeviceStore = deviceStore;
        mMainExecutor = Objects.requireNonNull(mainExecutor, "mainExecutor");
        mTimeSource = Objects.requireNonNull(timeSource, "timeSource");
        mReverseRegistrar = reverseRegistrar;
        mScheduler = scheduler;
        mRandom = Objects.requireNonNull(random, "random");
        mChannel = Objects.requireNonNull(channel, "channel");
        mCallerFilter = callerFilter;
    }

    /**
     * Handles one Register request and replies through {@code replier}.
     *
     * <p>Never throws for malformed input: every problem becomes an {@code accepted=false}
     * response with a {@code reject_reason}.</p>
     */
    public void handle(@NonNull final RegisterRequest request, @NonNull final String remoteHost,
        @NonNull final Replier replier) {
        final long nowMs = mTimeSource.nowMs();
        if (!mGlobalLimiter.allow("", nowMs) || !mAddressLimiter.allow(remoteHost, nowMs)) {
            reply(replier, false, REASON_RATE_LIMITED, null);
            return;
        }

        final String validation = validate(request);
        if (validation != null) {
            reply(replier, false, validation, null);
            return;
        }

        final DeviceSummary self = mSelfSupplier.get();
        if (self == null) {
            reply(replier, false, REASON_UNAVAILABLE, null);
            return;
        }

        final DeviceSummary caller = request.getSelf();
        final Predicate<String> callerFilter = mCallerFilter;
        if (callerFilter != null && !callerFilter.test(caller.getDeviceId())) {
            reply(replier, false, REASON_NOT_VISIBLE, null);
            return;
        }

        learn(caller, remoteHost);
        reply(replier, true, null, self);
        maybeReverseRegister(request, caller, self, remoteHost, nowMs);
    }

    /**
     * Validates a request.
     *
     * @return the reject reason, or null when the request is acceptable
     */
    @Nullable
    public static String validate(@NonNull final RegisterRequest request) {
        if (!RegisterProtoMapper.isVersionCompatible(request.getProtocolVersion())) {
            return REASON_UNSUPPORTED_VERSION;
        }
        final DeviceSummary self = request.getSelf();
        final String deviceId = self.getDeviceId();
        final String fingerprint = self.getFingerprint();
        if (deviceId.trim().isEmpty() || fingerprint.trim().isEmpty()) {
            return REASON_MISSING_IDENTITY;
        }
        if (deviceId.length() != DEVICE_ID_LENGTH || !isPrintable(deviceId)) {
            return REASON_INVALID_IDENTITY;
        }
        if (fingerprint.length() > MAX_FINGERPRINT_LENGTH || !isPrintable(fingerprint)) {
            return REASON_INVALID_IDENTITY;
        }
        if (self.getName().length() > MAX_NAME_LENGTH || !isPrintable(self.getName())) {
            return REASON_INVALID_REQUEST;
        }
        if (self.getAddressesCount() > MAX_ADDRESSES) {
            return REASON_INVALID_REQUEST;
        }
        for (final com.obby.android.localscreenshare.grpc.screenstream.DeviceAddress address
            : self.getAddressesList()) {
            if (address.getHost().trim().isEmpty() || !isPrintable(address.getHost())
                || address.getPort() <= 0 || address.getPort() > 0xFFFF) {
                return REASON_INVALID_REQUEST;
            }
        }
        return null;
    }

    private void learn(@NonNull final DeviceSummary caller, @NonNull final String remoteHost) {
        if (mDeviceStore == null) {
            return;
        }
        final List<DeviceAddress> addresses = RegisterProtoMapper.toAddresses(caller, mChannel);
        final String host = addresses.isEmpty() ? remoteHost : addresses.get(0).getHost();
        final int port = addresses.isEmpty() ? 0 : addresses.get(0).getPort();
        mMainExecutor.execute(() -> {
            mDeviceStore.onObserved(RegisterProtoMapper.toObservation(caller, mChannel, host, port, null));
            if (port > 0) {
                mDeviceStore.confirm(caller.getDeviceId(), mChannel, host, port);
            }
        });
    }

    private void maybeReverseRegister(@NonNull final RegisterRequest request,
        @NonNull final DeviceSummary caller, @NonNull final DeviceSummary self,
        @NonNull final String remoteHost, final long nowMs) {
        if (!request.getAnnounces() || mReverseRegistrar == null || mScheduler == null) {
            return;
        }
        final List<DeviceAddress> addresses = RegisterProtoMapper.toAddresses(caller, mChannel);
        if (addresses.isEmpty() || !mReverseLimiter.allow("", nowMs)) {
            // Without a port in the summary the server can only answer a broadcast address of
            // unknown reachability, so it deliberately sends nothing back.
            return;
        }
        final DeviceAddress target = addresses.get(0);
        final long delayMs = mRandom.nextInt((int) RegisterConstants.REGISTER_REVERSE_MAX_DELAY_MS + 1);
        mScheduler.schedule(delayMs, () -> mReverseRegistrar.register(target, false, self,
            new RegisterClient.Callback() {
                @Override
                public void onSuccess(@NonNull final com.obby.android.localscreenshare.grpc.screenstream
                    .RegisterResponse response) {
                    // The reverse answer is informational; the caller was already answered.
                }

                @Override
                public void onFailure(@NonNull final String reason) {
                    // Best-effort; the caller may have stopped between request and reverse call.
                }
            }));
    }

    private void reply(@NonNull final Replier replier, final boolean accepted,
        @Nullable final String rejectReason, @Nullable final DeviceSummary self) {
        final RegisterResponse.Builder builder = RegisterResponse.newBuilder()
            .setProtocolVersion(ScreenStreamProtoMapper.PROTOCOL_VERSION)
            .setAccepted(accepted);
        if (rejectReason != null) {
            builder.setRejectReason(rejectReason);
        }
        if (self != null) {
            builder.setSelf(self);
        }
        replier.respond(builder.build());
    }

    private static boolean isPrintable(@NonNull final String value) {
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return false;
            }
        }
        return true;
    }

    /**
     * Sliding-window counter keyed by remote address, with one global key.
     *
     * <p>Timestamps are pruned on every call, so memory is proportional to the number of callers
     * within the window and not to the number of devices ever seen.</p>
     */
    private static final class RateLimiter {
        private final int mCapacity;

        private final long mWindowMs;

        @NonNull
        private final Map<String, Deque<Long>> mEvents = new HashMap<>();

        private RateLimiter(final int capacity, final long windowMs) {
            mCapacity = capacity;
            mWindowMs = windowMs;
        }

        private synchronized boolean allow(@NonNull final String key, final long nowMs) {
            final Deque<Long> events = mEvents.computeIfAbsent(key, ignored -> new ArrayDeque<>());
            while (!events.isEmpty() && nowMs - events.peekFirst() >= mWindowMs) {
                events.pollFirst();
            }
            if (events.size() >= mCapacity) {
                return false;
            }
            events.addLast(nowMs);
            return true;
        }
    }
}
