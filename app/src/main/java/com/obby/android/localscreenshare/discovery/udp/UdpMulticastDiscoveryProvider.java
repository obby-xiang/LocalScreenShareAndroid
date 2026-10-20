package com.obby.android.localscreenshare.discovery.udp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.core.stream.TimeSource;
import com.obby.android.localscreenshare.discovery.model.DeviceAddress;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.provider.DeviceObservation;
import com.obby.android.localscreenshare.discovery.provider.DiscoveryProvider;
import com.obby.android.localscreenshare.discovery.provider.DiscoveryProviderListener;
import com.obby.android.localscreenshare.discovery.register.RegisterClient;
import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterResponse;
import com.obby.android.localscreenshare.support.NetUtils;

import java.net.InetAddress;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Discovery provider that receives multicast announcements and confirms them over Register.
 *
 * <p>Every accepted announcement becomes a {@link DeviceObservation} with
 * {@link DiscoveryChannel#MULTICAST}; it never confirms reachability by itself. When the
 * announcement carries a gRPC port and the device is not this one, the provider also starts a
 * Register call, merged per address by {@link MulticastAnnouncementFilter}. Only a successful
 * Register — accepted, compatible and carrying the peer's identity — reaches the injected
 * {@link ConfirmationSink}, which is the store's confirmation path.</p>
 *
 * <p>The provider holds the shared multicast lock while it runs and releases it when it stops, so
 * the receiver keeps the Wi-Fi chip in multicast mode only while someone is listening. Datagrams
 * are decoded on the receiver threads and every state change, including deduplication and the
 * Register trigger, runs on the injected main-thread executor.</p>
 */
public final class UdpMulticastDiscoveryProvider implements DiscoveryProvider {

    /** Stable provider id. */
    public static final String PROVIDER_ID = "multicast";

    /** Creates the transport used by one provider start. */
    @FunctionalInterface
    public interface TransportFactory {
        /** Returns a not yet started transport. */
        @NonNull
        MulticastTransport create();
    }

    /** Receives a peer whose Register call succeeded. */
    @FunctionalInterface
    public interface ConfirmationSink {
        /**
         * Marks the peer's address as confirmed.
         *
         * @param peer summary returned by the peer
         * @param host address the announcement came from
         * @param port gRPC port the peer announced
         */
        void onConfirmed(@NonNull DeviceSummary peer, @NonNull String host, int port);
    }

    @NonNull
    private final TransportFactory mTransportFactory;

    @NonNull
    private final MulticastLockManager mLockManager;

    @NonNull
    private final MulticastAnnouncementFilter mFilter;

    @NonNull
    private final TimeSource mTimeSource;

    @NonNull
    private final Executor mMainExecutor;

    @Nullable
    private final RegisterClient mRegisterClient;

    @Nullable
    private final Supplier<DeviceSummary> mSelfSummarySupplier;

    @Nullable
    private final ConfirmationSink mConfirmationSink;

    @Nullable
    private final BiPredicate<String, Integer> mRecentlyConfirmed;

    @Nullable
    private MulticastTransport mTransport;

    @Nullable
    private volatile DiscoveryProviderListener mListener;

    private boolean mStarted;

    /**
     * Creates the provider.
     *
     * @param transportFactory transport factory, called on every start
     * @param lockManager shared multicast lock
     * @param selfIdFilter returns true for this device's own id
     * @param timeSource clock of the merge window
     * @param mainExecutor main-thread executor that receives every state change
     * @param registerClient optional Register client; without it announcements are only heard
     * @param selfSummarySupplier this device's Register summary; may return null when unavailable
     * @param confirmationSink optional sink fed by a successful Register
     */
    public UdpMulticastDiscoveryProvider(@NonNull final TransportFactory transportFactory,
        @NonNull final MulticastLockManager lockManager, @NonNull final Predicate<String> selfIdFilter,
        @NonNull final TimeSource timeSource, @NonNull final Executor mainExecutor,
        @Nullable final RegisterClient registerClient,
        @Nullable final Supplier<DeviceSummary> selfSummarySupplier,
        @Nullable final ConfirmationSink confirmationSink) {
        this(transportFactory, lockManager, selfIdFilter, timeSource, mainExecutor, registerClient,
            selfSummarySupplier, confirmationSink, null);
    }

    /**
     * Creates the provider with a re-confirmation gate.
     *
     * @param recentlyConfirmed returns true when {@code (host, port)} was confirmed recently and
     *     does not need another handshake; null confirms on every merged announcement
     */
    public UdpMulticastDiscoveryProvider(@NonNull final TransportFactory transportFactory,
        @NonNull final MulticastLockManager lockManager, @NonNull final Predicate<String> selfIdFilter,
        @NonNull final TimeSource timeSource, @NonNull final Executor mainExecutor,
        @Nullable final RegisterClient registerClient,
        @Nullable final Supplier<DeviceSummary> selfSummarySupplier,
        @Nullable final ConfirmationSink confirmationSink,
        @Nullable final BiPredicate<String, Integer> recentlyConfirmed) {
        mTransportFactory = Objects.requireNonNull(transportFactory, "transportFactory");
        mLockManager = Objects.requireNonNull(lockManager, "lockManager");
        mTimeSource = Objects.requireNonNull(timeSource, "timeSource");
        mMainExecutor = Objects.requireNonNull(mainExecutor, "mainExecutor");
        mFilter = new MulticastAnnouncementFilter(selfIdFilter, timeSource);
        mRegisterClient = registerClient;
        mSelfSummarySupplier = selfSummarySupplier;
        mConfirmationSink = confirmationSink;
        mRecentlyConfirmed = recentlyConfirmed;
    }

    @Override
    public void start(@NonNull final DiscoveryProviderListener listener) {
        mListener = listener;
        if (mStarted) {
            return;
        }
        mStarted = true;
        mLockManager.acquire();

        final MulticastTransport transport;
        try {
            transport = mTransportFactory.create();
        } catch (RuntimeException e) {
            reportError("create transport failed: " + e.getMessage());
            mLockManager.release();
            mStarted = false;
            return;
        }
        mTransport = transport;
        transport.start(new MulticastTransport.Listener() {
            @Override
            public void onPacket(@NonNull final byte[] payload, @NonNull final InetAddress sender) {
                handlePacket(payload, sender);
            }

            @Override
            public void onTransportError(@NonNull final String reason) {
                reportError(reason);
            }
        });
    }

    @Override
    public void stop() {
        mListener = null;
        if (!mStarted) {
            return;
        }
        mStarted = false;
        final MulticastTransport transport = mTransport;
        mTransport = null;
        if (transport != null) {
            transport.close();
        }
        if (mRegisterClient != null) {
            mRegisterClient.cancelAll();
        }
        mFilter.reset();
        mLockManager.release();
    }

    @NonNull
    @Override
    public String id() {
        return PROVIDER_ID;
    }

    /** Decodes one datagram on the receiver thread and hands it to the main thread. */
    private void handlePacket(@NonNull final byte[] payload, @NonNull final InetAddress sender) {
        final Announcement announcement = AnnouncementCodec.decode(payload);
        if (announcement == null) {
            return;
        }
        final String host = NetUtils.getHostAddress(sender);
        mMainExecutor.execute(() -> handleAnnouncement(announcement, host));
    }

    /** Applies the admission rules and Register trigger; runs on the main thread. */
    private void handleAnnouncement(@NonNull final Announcement announcement, @NonNull final String host) {
        if (!mStarted || !mFilter.accept(announcement)) {
            return;
        }
        final DiscoveryProviderListener listener = mListener;
        if (listener == null) {
            return;
        }

        final DeviceObservation observation = announcement.toObservation(host, mTimeSource.nowMs());
        listener.onObserved(observation);

        if (announcement.isGoodbye() || announcement.getPort() <= 0) {
            return;
        }
        final BiPredicate<String, Integer> recentlyConfirmed = mRecentlyConfirmed;
        if (recentlyConfirmed != null && recentlyConfirmed.test(host, announcement.getPort())) {
            return;
        }
        if (!mFilter.shouldRegister(host, announcement.getPort())) {
            return;
        }
        triggerRegister(host, announcement.getPort());
    }

    private void triggerRegister(@NonNull final String host, final int port) {
        final RegisterClient registerClient = mRegisterClient;
        final Supplier<DeviceSummary> selfSupplier = mSelfSummarySupplier;
        if (registerClient == null || selfSupplier == null) {
            return;
        }
        final DeviceSummary self = selfSupplier.get();
        if (self == null) {
            return;
        }

        registerClient.register(new DeviceAddress(host, port, DiscoveryChannel.MULTICAST), true, self,
            new RegisterClient.Callback() {
                @Override
                public void onSuccess(@NonNull final RegisterResponse response) {
                    final ConfirmationSink sink = mConfirmationSink;
                    if (sink != null && mStarted) {
                        sink.onConfirmed(response.getSelf(), host, port);
                    }
                }

                @Override
                public void onFailure(@NonNull final String reason) {
                    // The device stays at HEARD; a later heartbeat triggers another attempt.
                }
            });
    }

    private void reportError(@NonNull final String reason) {
        final DiscoveryProviderListener listener = mListener;
        if (listener != null) {
            mMainExecutor.execute(() -> {
                final DiscoveryProviderListener current = mListener;
                if (current != null) {
                    current.onProviderError(reason);
                }
            });
        }
    }
}
