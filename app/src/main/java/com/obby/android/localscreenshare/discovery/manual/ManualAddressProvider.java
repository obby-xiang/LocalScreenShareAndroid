package com.obby.android.localscreenshare.discovery.manual;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.discovery.favorite.FavoriteDevice;
import com.obby.android.localscreenshare.discovery.favorite.FavoriteStore;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.provider.DiscoveryProvider;
import com.obby.android.localscreenshare.discovery.provider.DiscoveryProviderListener;
import com.obby.android.localscreenshare.discovery.scan.AddressProbe;
import com.obby.android.localscreenshare.discovery.scan.ProbeResult;
import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;

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

    private static final int FAVORITE_PROBE_THREADS = 4;

    private static final int FAVORITE_PROBE_QUEUE = 64;

    @NonNull
    private final AddressProbe mProbe;

    @NonNull
    private final FavoriteStore mFavorites;

    @NonNull
    private final Executor mMainExecutor;

    @NonNull
    private final ConfirmationSink mSink;

    private final long mProbeTimeoutMs;

    @NonNull
    private final ThreadPoolExecutor mProbeExecutor;

    private volatile boolean mStarted;

    /**
     * Creates the provider.
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
        mProbe = Objects.requireNonNull(probe, "probe");
        mFavorites = Objects.requireNonNull(favorites, "favorites");
        mMainExecutor = Objects.requireNonNull(mainExecutor, "mainExecutor");
        mSink = Objects.requireNonNull(sink, "sink");
        mProbeTimeoutMs = probeTimeoutMs;
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
                final ProbeResult result = mProbe.probe(host, port, mProbeTimeoutMs);
                mMainExecutor.execute(() -> {
                    if (result.isConfirmed() && result.getSummary() != null) {
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
     * Stops the probe executor; only the owner calls this when the provider is discarded.
     */
    public void shutdown() {
        mProbeExecutor.shutdownNow();
    }

    private void probeFavorite(@NonNull final FavoriteDevice target) {
        if (!mStarted) {
            return;
        }
        final ProbeResult result = mProbe.probe(target.getHost(), target.getPort(), mProbeTimeoutMs);
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
}
