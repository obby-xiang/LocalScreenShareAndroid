package com.obby.android.localscreenshare.discovery;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.App;
import com.obby.android.localscreenshare.support.Constants;
import com.obby.android.localscreenshare.support.Preferences;
import com.obby.android.localscreenshare.utils.NetUtils;
import com.obby.android.localscreenshare.utils.NsdUtils;
import com.obby.android.localscreenshare.utils.ThreadUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Process-wide NSD discovery manager that publishes the LAN screen-stream servers it can reach.
 *
 * <p>Discovery follows the NSD discovery/resolve chain: {@link NsdManager} reports a found service,
 * the manager resolves it asynchronously and converts the service attributes into an
 * {@link LssServiceInfo}. Entries are keyed by NSD service name; services that do not expose the
 * required attributes or that belong to this device (same service id) are dropped, and entries
 * with the same server id are collapsed to the most recent resolve. Every state change re-sorts
 * the snapshot by host address, port, name and id before notifying listeners.</p>
 *
 * <p><b>Threading.</b> All manager state is confined to the main thread: raw NSD callbacks are
 * marshaled by the listener wrapper, the resolver delivers its result on the main thread, and
 * {@link LssServiceDiscoveryListener} callbacks are therefore main-thread callbacks. The public
 * methods must be called on the main thread. Listener notifications carry an unmodifiable snapshot
 * that is replaced, never mutated, on change.</p>
 *
 * <p><b>Lifecycle.</b> Discovery starts when the first listener subscribes with
 * {@link #discoverServices(LssServiceDiscoveryListener)} and stops when the last listener leaves
 * through {@link #stopServiceDiscovery(LssServiceDiscoveryListener)}. Subscribing does not clear
 * cached results: a new listener immediately receives the current snapshot.</p>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LssServiceDiscoveryManager {
    private static final String TAG = "LssServiceDiscoveryManager";

    // Main-thread state: true while NsdManager discovery is running.
    private boolean mIsDiscovering;

    // Main-thread snapshot; unmodifiable and replaced whenever the discovered set changes.
    @NonNull
    private List<LssServiceInfo> mServiceInfoList = Collections.emptyList();

    @NonNull
    private final NsdManager mNsdManager = App.get().getSystemService(NsdManager.class);

    // NSD service name -> last observed raw service, used to detect losses during resolution.
    @NonNull
    private final Map<String, NsdServiceInfo> mNsdServiceInfoMap = new ConcurrentHashMap<>();

    // NSD service name -> resolved service; duplicates by service id are collapsed here.
    @NonNull
    private final Map<String, LssServiceInfo> mServiceInfoMap = new ConcurrentHashMap<>();

    // Copy-on-write so dispatch can iterate while a listener removes itself.
    @NonNull
    private final List<LssServiceDiscoveryListener> mServiceDiscoveryListeners = new CopyOnWriteArrayList<>();

    @NonNull
    private final NsdManager.DiscoveryListener mNsdDiscoveryListener =
        new NsdDiscoveryListenerWrapper(new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, String.format("mNsdDiscoveryListener.onStartDiscoveryFailed: start discovery failed"
                    + ", serviceType = %s, errorCode = %d", serviceType, errorCode));
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, String.format("mNsdDiscoveryListener.onStopDiscoveryFailed: stop discovery failed"
                    + ", serviceType = %s, errorCode = %d", serviceType, errorCode));
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.i(TAG, String.format("mNsdDiscoveryListener.onDiscoveryStarted: discovery started"
                    + ", serviceType = %s", serviceType));
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i(TAG, String.format("mNsdDiscoveryListener.onDiscoveryStopped: discovery stopped"
                    + ", serviceType = %s", serviceType));
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                Log.i(TAG, String.format("mNsdDiscoveryListener.onServiceFound: service found, serviceInfo = %s",
                    serviceInfo));

                if (TextUtils.isEmpty(serviceInfo.getServiceName())) {
                    return;
                }

                final String serviceName = serviceInfo.getServiceName();
                mNsdServiceInfoMap.put(serviceName, serviceInfo);

                // Resolve asynchronously; NsdUtils returns the result on the main thread.
                NsdUtils.resolveService(mNsdManager, serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                        // The service may have been lost while resolving; ignore a stale result.
                        if (!mNsdServiceInfoMap.containsKey(serviceName)) {
                            return;
                        }
                        Log.e(TAG, String.format("mNsdDiscoveryListener.onServiceFound: service resolve failed"
                            + ", serviceInfo = %s, errorCode = %d", serviceInfo, errorCode));
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo serviceInfo) {
                        // The service may have been lost while resolving; ignore a stale result.
                        if (!mNsdServiceInfoMap.containsKey(serviceName)) {
                            return;
                        }

                        final LssServiceInfo lssServiceInfo = buildServiceInfo(serviceInfo);
                        Log.i(TAG, String.format("mNsdDiscoveryListener.onServiceFound: service resolved"
                            + ", serviceInfo = %s, lssServiceInfo = %s", serviceInfo, lssServiceInfo));

                        mNsdServiceInfoMap.put(serviceName, serviceInfo);

                        // Ignore the device's own advertisement and stale entries for the same
                        // service id (for example after a re-registration with a new NSD name).
                        if (lssServiceInfo == null
                            || Objects.equals(lssServiceInfo.getId(), Preferences.get().getServiceId())) {
                            mServiceInfoMap.remove(serviceName);
                        } else {
                            mServiceInfoMap.entrySet()
                                .stream()
                                .filter(entry -> Objects.equals(entry.getValue().getId(), lssServiceInfo.getId()))
                                .map(Map.Entry::getKey)
                                .forEach(mServiceInfoMap::remove);
                            mServiceInfoMap.put(serviceName, lssServiceInfo);
                        }

                        updateServiceInfoList();
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                // Removing the raw entry first makes any in-flight resolve callback for this service
                // stale, so it cannot resurrect the lost entry.
                Log.i(TAG, String.format("mNsdDiscoveryListener.onServiceLost: service lost, serviceInfo = %s",
                    serviceInfo));
                if (!TextUtils.isEmpty(serviceInfo.getServiceName())) {
                    mNsdServiceInfoMap.remove(serviceInfo.getServiceName());
                    mServiceInfoMap.remove(serviceInfo.getServiceName());
                    updateServiceInfoList();
                }
            }
        }, ThreadUtils.getMainThreadExecutor());

    @NonNull
    public static LssServiceDiscoveryManager get() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * Subscribes a listener and starts NSD discovery if it was not running.
     *
     * <p>Must be called on the main thread. A listener that is already subscribed is ignored.
     * Otherwise the listener receives the current snapshot synchronously before this method
     * returns, whether or not discovery was just started.</p>
     */
    public void discoverServices(@NonNull final LssServiceDiscoveryListener listener) {
        Log.i(TAG, "discoverServices: discover services");

        if (mServiceDiscoveryListeners.contains(listener)) {
            Log.w(TAG, "discoverServices: listener already exists");
            return;
        }

        mServiceDiscoveryListeners.add(listener);
        if (!mIsDiscovering) {
            mIsDiscovering = true;
            startDiscovery();
        }

        listener.onServicesChanged(mServiceInfoList);
    }

    /**
     * Unsubscribes a listener and stops NSD discovery once no listener remains.
     *
     * <p>Must be called on the main thread. Stopping discovery clears both internal maps; the
     * resulting empty snapshot is not broadcast because the listener list is empty at that
     * point.</p>
     */
    public void stopServiceDiscovery(@NonNull final LssServiceDiscoveryListener listener) {
        Log.i(TAG, "stopServiceDiscovery: stop service discovery");

        if (!mServiceDiscoveryListeners.remove(listener)) {
            Log.w(TAG, "stopServiceDiscovery: listener does not exist");
            return;
        }

        if (mServiceDiscoveryListeners.isEmpty() && mIsDiscovering) {
            mIsDiscovering = false;
            stopDiscovery();
        }
    }

    /**
     * Starts NSD discovery and leaves it running until the last listener unsubscribes.
     *
     * <p>Must be called on the main thread; the discovery callbacks are marshaled back to it by
     * {@link NsdDiscoveryListenerWrapper}.</p>
     */
    private void startDiscovery() {
        Log.i(TAG, "startDiscovery: start discovery");
        mNsdManager.discoverServices(Constants.NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, mNsdDiscoveryListener);
    }

    /**
     * Stops NSD discovery and clears cached services. The resulting empty snapshot is dispatched
     * while the listener list is already empty, so no listener observes it.
     *
     * <p>Must be called on the main thread, after the last listener has been removed.</p>
     */
    private void stopDiscovery() {
        Log.i(TAG, "stopDiscovery: stop discovery");
        mNsdManager.stopServiceDiscovery(mNsdDiscoveryListener);
        mNsdServiceInfoMap.clear();
        mServiceInfoMap.clear();
        updateServiceInfoList();
    }

    /**
     * Rebuilds the sorted snapshot and notifies listeners only when it differs from the previous
     * one. Equality relies on the value semantics of {@link LssServiceInfo}.
     */
    private void updateServiceInfoList() {
        final List<LssServiceInfo> serviceInfoList = new ArrayList<>(mServiceInfoMap.values());
        Collections.sort(serviceInfoList);

        if (Objects.equals(mServiceInfoList, serviceInfoList)) {
            Log.w(TAG, "updateServiceInfoList: service info list not changed");
            return;
        }

        mServiceInfoList = Collections.unmodifiableList(serviceInfoList);
        Log.i(TAG, String.format("updateServiceInfoList: service info list size is %d", mServiceInfoList.size()));

        mServiceDiscoveryListeners.forEach(listener -> listener.onServicesChanged(mServiceInfoList));
    }

    /**
     * Converts a resolved NSD service into a value object.
     *
     * @return the converted service, or {@code null} when the {@code id} or {@code name} TXT
     * attribute or the resolved host address is missing
     */
    @Nullable
    private LssServiceInfo buildServiceInfo(@NonNull final NsdServiceInfo serviceInfo) {
        final Map<String, byte[]> attributes = serviceInfo.getAttributes();
        final String id = Optional.ofNullable(attributes.get(Constants.NSD_SERVICE_ATTR_ID))
            .map(value -> new String(value, StandardCharsets.UTF_8))
            .orElse(null);
        final String name = Optional.ofNullable(attributes.get(Constants.NSD_SERVICE_ATTR_NAME))
            .map(value -> new String(value, StandardCharsets.UTF_8))
            .orElse(null);
        final String hostAddress = Optional.ofNullable(serviceInfo.getHost())
            .map(NetUtils::getHostAddress)
            .orElse(null);
        if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name) || TextUtils.isEmpty(hostAddress)) {
            return null;
        }

        return LssServiceInfo.builder()
            .id(id)
            .name(name)
            .hostAddress(hostAddress)
            .port(serviceInfo.getPort())
            .build();
    }

    /**
     * Forwards every {@link NsdManager.DiscoveryListener} callback onto the given executor; the
     * manager uses the main-thread executor so its state stays confined to the main thread.
     */
    private static class NsdDiscoveryListenerWrapper implements NsdManager.DiscoveryListener {
        @NonNull
        private final NsdManager.DiscoveryListener mListener;

        @NonNull
        private final Executor mExecutor;

        private NsdDiscoveryListenerWrapper(@NonNull final NsdManager.DiscoveryListener listener,
            @NonNull final Executor executor) {
            mListener = listener;
            mExecutor = executor;
        }

        @Override
        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            mExecutor.execute(() -> mListener.onStartDiscoveryFailed(serviceType, errorCode));
        }

        @Override
        public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            mExecutor.execute(() -> mListener.onStopDiscoveryFailed(serviceType, errorCode));
        }

        @Override
        public void onDiscoveryStarted(String serviceType) {
            mExecutor.execute(() -> mListener.onDiscoveryStarted(serviceType));
        }

        @Override
        public void onDiscoveryStopped(String serviceType) {
            mExecutor.execute(() -> mListener.onDiscoveryStopped(serviceType));
        }

        @Override
        public void onServiceFound(NsdServiceInfo serviceInfo) {
            mExecutor.execute(() -> mListener.onServiceFound(serviceInfo));
        }

        @Override
        public void onServiceLost(NsdServiceInfo serviceInfo) {
            mExecutor.execute(() -> mListener.onServiceLost(serviceInfo));
        }
    }

    // Holds the process-wide instance; initialized on the first call to get().
    private static class InstanceHolder {
        private static final LssServiceDiscoveryManager INSTANCE = new LssServiceDiscoveryManager();
    }
}
