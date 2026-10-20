package com.obby.android.localscreenshare.discovery.nsd;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.core.util.TimeSource;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.provider.DeviceObservation;
import com.obby.android.localscreenshare.discovery.provider.DiscoveryProvider;
import com.obby.android.localscreenshare.discovery.provider.DiscoveryProviderListener;
import com.obby.android.localscreenshare.support.BackoffPolicy;
import com.obby.android.localscreenshare.support.NetUtils;
import com.obby.android.localscreenshare.support.ThreadUtils;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

/**
 * NSD discovery provider for the current and the legacy service type.
 *
 * <p>Both types are discovered in parallel so a new client still sees servers that have not
 * upgraded, and so this client is seen by servers that only advertise the legacy type. Each type
 * keeps its own listener, running state and retry schedule: a failed start only retries that type
 * and never stops the other one.</p>
 *
 * <p>Resolving runs through {@link NsdResolver} with a two-second timeout, so a service that never
 * answers cannot block the single resolver thread. Connectivity changes restart discovery after a
 * debounce, which is how the provider recovers when the Wi-Fi network is replaced underneath it.
 * Observations never carry a confirmation: the provider only reports what it heard.</p>
 *
 * <p><b>Threading.</b> {@link #start(DiscoveryProviderListener)} and {@link #stop()} must be
 * called on the main thread; every framework callback is marshaled to the main thread before it
 * touches provider state or the listener.</p>
 */
public final class NsdDiscoveryProvider implements DiscoveryProvider {

    /** Stable provider id. */
    public static final String PROVIDER_ID = "nsd";

    /** Resolve timeout; keeps one unresponsive service from blocking the resolver queue. */
    public static final long RESOLVE_TIMEOUT_MS = 2_000L;

    private static final String TAG = "NsdDiscoveryProvider";

    @NonNull
    private final NsdManager mNsdManager;

    @Nullable
    private final ConnectivityManager mConnectivityManager;

    @NonNull
    private final Executor mMainExecutor;

    @NonNull
    private final TimeSource mTimeSource;

    @NonNull
    private final Predicate<String> mSelfIdFilter;

    @NonNull
    private final List<String> mServiceTypes;

    @NonNull
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // Service type -> listener; created once so restart reuses the framework registration object.
    @NonNull
    private final Map<String, NsdManager.DiscoveryListener> mDiscoveryListeners = new LinkedHashMap<>();

    // serviceType + "|" + serviceName -> last found service and what resolution learned from it.
    @NonNull
    private final Map<String, TrackedService> mTrackedServices = new LinkedHashMap<>();

    @Nullable
    private DiscoveryProviderListener mListener;

    @Nullable
    private NsdDiscoveryCoordinator mCoordinator;

    private boolean mStarted;

    /**
     * Creates the provider.
     *
     * @param context context used for the connectivity manager
     * @param timeSource clock stamped onto observations
     * @param selfIdFilter returns true for this device's own id, so self-advertisements are dropped
     */
    public NsdDiscoveryProvider(@NonNull final Context context, @NonNull final TimeSource timeSource,
        @NonNull final Predicate<String> selfIdFilter) {
        this(context, timeSource, selfIdFilter,
            Arrays.asList(NsdConstants.NSD_SERVICE_TYPE, NsdConstants.NSD_SERVICE_TYPE_LEGACY));
    }

    /**
     * Creates the provider with an explicit service-type list.
     *
     * @param context context used for the connectivity manager
     * @param timeSource clock stamped onto observations
     * @param selfIdFilter returns true for this device's own id
     * @param serviceTypes service types to discover
     */
    public NsdDiscoveryProvider(@NonNull final Context context, @NonNull final TimeSource timeSource,
        @NonNull final Predicate<String> selfIdFilter, @NonNull final List<String> serviceTypes) {
        mNsdManager = context.getSystemService(NsdManager.class);
        mConnectivityManager = context.getSystemService(ConnectivityManager.class);
        mMainExecutor = ThreadUtils.getMainThreadExecutor();
        mTimeSource = timeSource;
        mSelfIdFilter = selfIdFilter;
        mServiceTypes = serviceTypes;
        for (final String serviceType : serviceTypes) {
            mDiscoveryListeners.put(serviceType, new TypeDiscoveryListener(serviceType));
        }
    }

    @Override
    public void start(@NonNull final DiscoveryProviderListener listener) {
        mListener = listener;
        if (mStarted) {
            return;
        }
        mStarted = true;
        mCoordinator = new NsdDiscoveryCoordinator(new NsdDiscovery(), new MainThreadScheduler(),
            mServiceTypes, BackoffPolicy::defaults);
        mCoordinator.startAll();

        if (mConnectivityManager != null) {
            try {
                mConnectivityManager.registerDefaultNetworkCallback(mNetworkCallback);
            } catch (RuntimeException e) {
                Log.e(TAG, "start: register network callback failed", e);
            }
        }
    }

    @Override
    public void stop() {
        mListener = null;
        if (!mStarted) {
            return;
        }
        mStarted = false;

        if (mCoordinator != null) {
            mCoordinator.stopAll();
            mCoordinator = null;
        }
        if (mConnectivityManager != null) {
            try {
                mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
            } catch (RuntimeException e) {
                // Already unregistered; nothing to release.
            }
        }
        mTrackedServices.clear();
    }

    @NonNull
    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @NonNull
    private static String trackKey(@NonNull final String serviceType, @NonNull final String serviceName) {
        return serviceType + "|" + serviceName;
    }

    private void deliver(@NonNull final DeviceObservation observation) {
        if (observation.isLost() && observation.getId() == null) {
            return;
        }
        if (observation.getId() != null && mSelfIdFilter.test(observation.getId())) {
            return;
        }
        final DiscoveryProviderListener listener = mListener;
        if (listener != null) {
            listener.onObserved(observation);
        }
    }

    private void reportError(@NonNull final String reason) {
        final DiscoveryProviderListener listener = mListener;
        if (listener != null) {
            listener.onProviderError(reason);
        }
    }

    private void handleFound(@NonNull final String serviceType, @NonNull final NsdServiceInfo serviceInfo) {
        if (!mStarted || TextUtils.isEmpty(serviceInfo.getServiceName())) {
            return;
        }
        final String key = trackKey(serviceType, serviceInfo.getServiceName());
        if (mTrackedServices.get(key) == null) {
            mTrackedServices.put(key, new TrackedService(serviceInfo));
        }

        NsdResolver.resolveService(mNsdManager, serviceInfo, RESOLVE_TIMEOUT_MS, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                Log.w(TAG, String.format("handleFound: resolve failed, serviceType = %s, errorCode = %d",
                    serviceType, errorCode));
            }

            @Override
            public void onServiceResolved(NsdServiceInfo info) {
                handleResolved(key, serviceType, info);
            }
        });
    }

    private void handleResolved(@NonNull final String key, @NonNull final String serviceType,
        @NonNull final NsdServiceInfo serviceInfo) {
        final TrackedService tracked = mTrackedServices.get(key);
        if (!mStarted || tracked == null) {
            // Lost or stopped while resolving; the result is stale.
            return;
        }

        final String hostAddress = serviceInfo.getHost() == null
            ? null : NetUtils.getHostAddress(serviceInfo.getHost());
        final DeviceObservation observation = NsdTxtRecord.observe(serviceInfo.getAttributes(), hostAddress,
            serviceInfo.getPort(), mTimeSource.nowMs());
        if (observation == null) {
            Log.w(TAG, String.format("handleResolved: unusable record, serviceType = %s, serviceInfo = %s",
                serviceType, serviceInfo));
            return;
        }
        if (mSelfIdFilter.test(observation.getId())) {
            mTrackedServices.remove(key);
            return;
        }

        tracked.id = observation.getId();
        tracked.host = observation.getHost();
        tracked.port = observation.getPort();
        deliver(observation);
    }

    private void handleLost(@NonNull final String serviceType, @NonNull final NsdServiceInfo serviceInfo) {
        if (TextUtils.isEmpty(serviceInfo.getServiceName())) {
            return;
        }
        final TrackedService tracked = mTrackedServices.remove(trackKey(serviceType, serviceInfo.getServiceName()));
        if (!mStarted || tracked == null || tracked.id == null || tracked.host == null) {
            return;
        }
        deliver(DeviceObservation.builder()
            .source(DiscoveryChannel.NSD)
            .id(tracked.id)
            .host(tracked.host)
            .port(tracked.port)
            .lastSeenMs(mTimeSource.nowMs())
            .lost(true)
            .build());
    }

    /** Forwards NsdManager discovery callbacks for one service type onto the main thread. */
    private final class TypeDiscoveryListener implements NsdManager.DiscoveryListener {
        @NonNull
        private final String mServiceType;

        private TypeDiscoveryListener(@NonNull final String serviceType) {
            mServiceType = serviceType;
        }

        @Override
        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            Log.e(TAG, String.format("onStartDiscoveryFailed: serviceType = %s, errorCode = %d",
                mServiceType, errorCode));
            mMainExecutor.execute(() -> {
                if (mCoordinator != null) {
                    mCoordinator.onStartFailed(mServiceType);
                }
                reportError("start discovery failed: " + mServiceType + " (" + errorCode + ")");
            });
        }

        @Override
        public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            Log.e(TAG, String.format("onStopDiscoveryFailed: serviceType = %s, errorCode = %d",
                mServiceType, errorCode));
        }

        @Override
        public void onDiscoveryStarted(String serviceType) {
            Log.i(TAG, String.format("onDiscoveryStarted: serviceType = %s", mServiceType));
            mMainExecutor.execute(() -> {
                if (mCoordinator != null) {
                    mCoordinator.onDiscoveryStarted(mServiceType);
                }
            });
        }

        @Override
        public void onDiscoveryStopped(String serviceType) {
            Log.i(TAG, String.format("onDiscoveryStopped: serviceType = %s", mServiceType));
            mMainExecutor.execute(() -> {
                if (mCoordinator != null) {
                    mCoordinator.onDiscoveryStopped(mServiceType);
                }
            });
        }

        @Override
        public void onServiceFound(NsdServiceInfo serviceInfo) {
            mMainExecutor.execute(() -> handleFound(mServiceType, serviceInfo));
        }

        @Override
        public void onServiceLost(NsdServiceInfo serviceInfo) {
            mMainExecutor.execute(() -> handleLost(mServiceType, serviceInfo));
        }
    }

    /** Bridges the coordinator onto {@link NsdManager}. */
    private final class NsdDiscovery implements NsdDiscoveryCoordinator.Discovery {
        @Override
        public void startDiscovery(@NonNull String serviceType) {
            final NsdManager.DiscoveryListener listener = mDiscoveryListeners.get(serviceType);
            if (listener == null) {
                return;
            }
            try {
                mNsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener);
            } catch (RuntimeException e) {
                Log.e(TAG, String.format("startDiscovery: start failed, serviceType = %s", serviceType), e);
                // The framework may still consider this type active, for example when a late stop
                // callback cleared the local flag; stop it so the retry can start cleanly.
                try {
                    mNsdManager.stopServiceDiscovery(listener);
                } catch (RuntimeException ignored) {
                    // Nothing was running, so there is nothing to stop.
                }
                if (mCoordinator != null) {
                    mCoordinator.onStartFailed(serviceType);
                }
            }
        }

        @Override
        public void stopDiscovery(@NonNull String serviceType) {
            final NsdManager.DiscoveryListener listener = mDiscoveryListeners.get(serviceType);
            if (listener == null) {
                return;
            }
            try {
                mNsdManager.stopServiceDiscovery(listener);
            } catch (RuntimeException e) {
                // The framework never started this type, so there is nothing to stop.
            }
        }
    }

    /** Schedules coordinator work on the main handler. */
    private final class MainThreadScheduler implements NsdDiscoveryCoordinator.Scheduler {
        @NonNull
        @Override
        public NsdDiscoveryCoordinator.Cancellable schedule(long delayMs, @NonNull Runnable task) {
            mMainHandler.postDelayed(task, delayMs);
            return () -> mMainHandler.removeCallbacks(task);
        }
    }

    // Any connectivity change can invalidate the discovered set, so each event triggers the same
    // debounced restart. The coordinator ignores events when discovery is not wanted.
    @NonNull
    private final ConnectivityManager.NetworkCallback mNetworkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            onNetworkChanged();
        }

        @Override
        public void onLost(@NonNull Network network) {
            onNetworkChanged();
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
            onNetworkChanged();
        }

        private void onNetworkChanged() {
            mMainExecutor.execute(() -> {
                if (mCoordinator != null) {
                    mCoordinator.onNetworkChanged();
                }
            });
        }
    };

    /** Last found service and what resolution learned about it. */
    private static final class TrackedService {
        @NonNull
        final NsdServiceInfo serviceInfo;

        @Nullable
        String id;

        @Nullable
        String host;

        int port;

        TrackedService(@NonNull final NsdServiceInfo serviceInfo) {
            this.serviceInfo = serviceInfo;
        }
    }
}
