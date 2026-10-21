package com.obby.android.localscreenshare.discovery.manual;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.discovery.favorite.FavoriteDevice;
import com.obby.android.localscreenshare.discovery.favorite.FavoriteStore;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.provider.DiscoveryProvider;
import com.obby.android.localscreenshare.discovery.provider.DiscoveryProviderListener;
import com.obby.android.localscreenshare.discovery.scan.AddressProbe;
import com.obby.android.localscreenshare.discovery.scan.ProbeResult;
import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Discovery fallback that probes addresses the user already knows.
 *
 * <p>Two things are probed here. When discovery starts, every favorite with an address is probed,
 * most recent success first, so a device whose announcements are filtered still shows up as
 * confirmed on the list. On demand, an address typed by the user is probed and, when it answers,
 * confirmed and offered for saving as a favorite. Neither path trusts the address: only an
 * accepted Register with a usable identity reaches the sink.</p>
 *
 * <p>The provider is a {@link DiscoveryProvider} so its favorite probes start and stop with
 * discovery. Manual probes are allowed while it is stopped, because the user can add an address
 * even when automatic discovery found nothing; the sink writes into the same store either way.
 * Probe results are reported on the main-thread executor.</p>
 */
public final class ManualAddressProvider implements DiscoveryProvider {

    /**
     * Stable provider id.
     */
    public static final String PROVIDER_ID = "manual";

    /**
     * Receives a peer whose probe succeeded.
     */
    @FunctionalInterface
    public interface ConfirmationSink {
        /**
         * Marks the probed address as confirmed.
         *
         * @param channel channel that produced the probe
         * @param peer summary the peer returned
         * @param host address that answered
         * @param port port that answered
         */
        void onConfirmed(@NonNull DiscoveryChannel channel, @NonNull DeviceSummary peer,
            @NonNull String host, int port);
    }

    /**
     * Outcome of one manual probe, delivered on the main-thread executor.
     */
    public interface ProbeCallback {
        /**
         * The address answered and was handed to the confirmation sink.
         */
        void onConfirmed(@NonNull String host, int port, @NonNull DeviceSummary peer);

        /**
         * The address did not answer, or the input was invalid; {@code reason} is a short code.
         */
        void onFailed(@NonNull String reason);
    }

    /**
     * Receives a manual address that a periodic re-probe could not reach.
     */
    @FunctionalInterface
    public interface UnreachableSink {
        /**
         * Reports that {@code host:port} did not answer; used to degrade the stored entry.
         */
        void onUnreachable(@NonNull String host, int port);
    }

    private static final int FAVORITE_PROBE_THREADS = 4;

    private static final int FAVORITE_PROBE_QUEUE = 64;

    /**
     * Reason reported when the probe itself threw instead of returning a failed result.
     */
    private static final String REASON_PROBE_FAILED = "probe_failed";

    @NonNull
    private final AddressProbe mProbe;

    @NonNull
    private final FavoriteStore mFavorites;

    @NonNull
    private final Executor mMainExecutor;

    @NonNull
    private final ConfirmationSink mSink;

    private final long mProbeTimeoutMs;

    private final long mFirstProbeTimeoutMs;

    // Set once one probe has been answered. Until then every probe uses mFirstProbeTimeoutMs,
    // because the first handshake of a process loads the identity key and initialises the TLS
    // stack; see ScanConstants.MANUAL_PROBE_FIRST_TIMEOUT_MS. Written by probe threads.
    private volatile boolean mIsWarm;

    @NonNull
    private final ThreadPoolExecutor mProbeExecutor;

    private volatile boolean mStarted;

    // Addresses the user typed and confirmed; re-probed periodically so they stay confirmed (or
    // degrade to unreachable) instead of expiring out of the list after the one-shot probe.
    @NonNull
    private final Map<String, ManualAddress> mManualTargets = new LinkedHashMap<>();

    // Optional listener notified when a periodic re-probe fails; null disables the callback.
    @Nullable
    private UnreachableSink mUnreachableSink;

    /**
     * Creates the provider, using one deadline for every probe.
     *
     * @param probe one-address probe
     * @param favorites favorites probed at start
     * @param mainExecutor executor that receives sink and callback invocations
     * @param sink confirmation sink
     * @param probeTimeoutMs deadline of one manual or favorite probe
     */
    public ManualAddressProvider(@NonNull final AddressProbe probe,
        @NonNull final FavoriteStore favorites, @NonNull final Executor mainExecutor,
        @NonNull final ConfirmationSink sink, final long probeTimeoutMs) {
        this(probe, favorites, mainExecutor, sink, probeTimeoutMs, probeTimeoutMs);
    }

    /**
     * Creates the provider with a separate cold-start deadline.
     *
     * <p>Every probe issued before one probe of this process has been answered uses
     * {@code firstProbeTimeoutMs}; afterwards {@code probeTimeoutMs} applies. The wider first
     * deadline covers the one-time cost of loading the identity key and initialising the TLS
     * stack, which a cold probe otherwise reports as a timeout while the address is in fact
     * reachable.</p>
     *
     * @param probe one-address probe
     * @param favorites favorites probed at start
     * @param mainExecutor executor that receives sink and callback invocations
     * @param sink confirmation sink
     * @param probeTimeoutMs deadline of a probe issued once the process is warm
     * @param firstProbeTimeoutMs deadline of a probe issued before the process is warm; must not
     *                            be shorter than {@code probeTimeoutMs} to be useful
     */
    public ManualAddressProvider(@NonNull final AddressProbe probe,
        @NonNull final FavoriteStore favorites, @NonNull final Executor mainExecutor,
        @NonNull final ConfirmationSink sink, final long probeTimeoutMs,
        final long firstProbeTimeoutMs) {
        mProbe = Objects.requireNonNull(probe, "probe");
        mFavorites = Objects.requireNonNull(favorites, "favorites");
        mMainExecutor = Objects.requireNonNull(mainExecutor, "mainExecutor");
        mSink = Objects.requireNonNull(sink, "sink");
        mProbeTimeoutMs = probeTimeoutMs;
        mFirstProbeTimeoutMs = firstProbeTimeoutMs;
        final AtomicInteger threadIds = new AtomicInteger();
        mProbeExecutor = new ThreadPoolExecutor(FAVORITE_PROBE_THREADS, FAVORITE_PROBE_THREADS,
            0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(FAVORITE_PROBE_QUEUE),
            runnable -> {
                final Thread thread = new Thread(runnable,
                    "lss-manual-probe-" + threadIds.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
    }

    @Override
    public void start(@NonNull final DiscoveryProviderListener listener) {
        mStarted = true;
        for (final FavoriteDevice target : mFavorites.probeTargets()) {
            mProbeExecutor.execute(() -> probeFavorite(target));
        }
    }

    @Override
    public void stop() {
        mStarted = false;
    }

    @NonNull
    @Override
    public String id() {
        return PROVIDER_ID;
    }

    /**
     * Probes one user-entered address.
     *
     * @param input text as typed
     * @param defaultPort port used when the entry omits one
     * @param callback outcome callback, invoked on the main-thread executor
     * @return false when the input could not be parsed; the callback still runs
     */
    public boolean probeManual(@NonNull final String input, final int defaultPort,
        @NonNull final ProbeCallback callback) {
        final ManualAddress address;
        try {
            address = ManualAddress.parse(input, defaultPort);
        } catch (IllegalArgumentException e) {
            mMainExecutor.execute(() -> callback.onFailed("invalid_address"));
            return false;
        }
        final String host = address.getHost();
        final int port = address.getPort();
        try {
            mProbeExecutor.execute(() -> {
                final ProbeResult result;
                try {
                    result = probe(host, port);
                } catch (RuntimeException e) {
                    // The probe contract returns failures instead of throwing, but a transport that
                    // breaks it used to leave the caller without a callback: no toast and no log,
                    // which reads as "the address did nothing". Report the failure instead.
                    mMainExecutor.execute(() -> callback.onFailed(REASON_PROBE_FAILED));
                    return;
                }
                mMainExecutor.execute(() -> {
                    if (result.isConfirmed() && result.getSummary() != null) {
                        mManualTargets.put(addressKey(host, port), address);
                        mSink.onConfirmed(DiscoveryChannel.MANUAL, result.getSummary(), host, port);
                        callback.onConfirmed(host, port, result.getSummary());
                    } else {
                        callback.onFailed(result.getReason());
                    }
                });
            });
        } catch (RuntimeException e) {
            mMainExecutor.execute(() -> callback.onFailed("probe_rejected"));
            return false;
        }
        return true;
    }

    /**
     * Probes one discovered address to learn the certificate identity behind it.
     *
     * <p>A multicast announcement carries no fingerprint, so the row a user taps has nothing to
     * confirm yet. One direct {@code Register} over TLS yields the peer's certificate fingerprint,
     * and a success is written to the store through the same confirmation sink as every other probe,
     * which is what makes the row connectable afterwards. The confirmation is recorded under {@link
     * DiscoveryChannel#MULTICAST}, because that is where the address was observed: recording it as a
     * manual entry would pin it, and a device the user merely tapped must keep expiring like any
     * other announced one. For the same reason the address is not remembered as a manual target.</p>
     *
     * @param host address of the discovered device
     * @param port gRPC port of the discovered device
     * @param callback outcome callback, invoked on the main-thread executor
     */
    public void probeIdentity(@NonNull final String host, final int port,
        @NonNull final ProbeCallback callback) {
        if (port <= 0 || port > 0xFFFF) {
            mMainExecutor.execute(() -> callback.onFailed("invalid_port"));
            return;
        }
        try {
            mProbeExecutor.execute(() -> {
                final ProbeResult result;
                try {
                    result = probe(host, port);
                } catch (RuntimeException e) {
                    mMainExecutor.execute(() -> callback.onFailed(REASON_PROBE_FAILED));
                    return;
                }
                mMainExecutor.execute(() -> {
                    if (result.isConfirmed() && result.getSummary() != null) {
                        mSink.onConfirmed(DiscoveryChannel.MULTICAST, result.getSummary(), host,
                            port);
                        callback.onConfirmed(host, port, result.getSummary());
                    } else {
                        callback.onFailed(result.getReason());
                    }
                });
            });
        } catch (RuntimeException e) {
            mMainExecutor.execute(() -> callback.onFailed("probe_rejected"));
        }
    }

    /**
     * Re-probes every manually added address so a reachable device stays confirmed and an
     * unreachable one degrades to unreachable instead of being removed by the store TTL.
     */
    public void probeManualTargets() {
        if (!mStarted) {
            return;
        }
        for (final ManualAddress target : mManualTargets.values()) {
            mProbeExecutor.execute(() -> probeManualTarget(target));
        }
    }

    /**
     * Returns the number of manually added addresses being re-probed.
     */
    public int manualTargetCount() {
        return mManualTargets.size();
    }

    /**
     * Sets the sink that is notified when a periodic re-probe fails; null disables the callback.
     */
    public void setUnreachableSink(@Nullable final UnreachableSink sink) {
        mUnreachableSink = sink;
    }

    private void probeManualTarget(@NonNull final ManualAddress target) {
        if (!mStarted) {
            return;
        }
        final ProbeResult result = probe(target.getHost(), target.getPort());
        if (!result.isConfirmed() || result.getSummary() == null || !mStarted) {
            final UnreachableSink sink = mUnreachableSink;
            if (sink != null && mStarted) {
                mMainExecutor.execute(() -> sink.onUnreachable(target.getHost(), target.getPort()));
            }
            return;
        }
        final DeviceSummary summary = result.getSummary();
        final String host = target.getHost();
        final int port = target.getPort();
        mMainExecutor.execute(() -> {
            if (mStarted) {
                mSink.onConfirmed(DiscoveryChannel.MANUAL, summary, host, port);
            }
        });
    }

    @NonNull
    private static String addressKey(@NonNull final String host, final int port) {
        return host + ":" + port;
    }

    /**
     * Stops the probe executor; only the owner calls this when the provider is discarded.
     */
    public void shutdown() {
        mProbeExecutor.shutdownNow();
    }

    private void probeFavorite(@NonNull final FavoriteDevice target) {
        if (!mStarted) {
            return;
        }
        final ProbeResult result = probe(target.getHost(), target.getPort());
        if (!result.isConfirmed() || result.getSummary() == null || !mStarted) {
            return;
        }
        final DeviceSummary summary = result.getSummary();
        final String host = target.getHost();
        final int port = target.getPort();
        mMainExecutor.execute(() -> {
            if (mStarted) {
                mSink.onConfirmed(DiscoveryChannel.FAVORITE, summary, host, port);
            }
        });
    }

    /**
     * Probes one address with the deadline that matches how warm this process is.
     *
     * <p>Until one probe is answered the wider cold deadline applies; a confirmed probe marks the
     * process warm, so every later probe fails fast again. Only runs on the probe executor.</p>
     *
     * @param host address to probe
     * @param port port to probe
     */
    @NonNull
    private ProbeResult probe(@NonNull final String host, final int port) {
        final ProbeResult result = mProbe.probe(host, port,
            mIsWarm ? mProbeTimeoutMs : mFirstProbeTimeoutMs);
        if (result.isConfirmed()) {
            mIsWarm = true;
        }
        return result;
    }
}
