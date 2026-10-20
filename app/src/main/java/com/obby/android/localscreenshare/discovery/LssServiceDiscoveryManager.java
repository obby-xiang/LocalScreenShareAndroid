package com.obby.android.localscreenshare.discovery;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.App;
import com.obby.android.localscreenshare.core.stream.TimeSource;
import com.obby.android.localscreenshare.discovery.nsd.NsdDiscoveryProvider;
import com.obby.android.localscreenshare.discovery.provider.DeviceObservation;
import com.obby.android.localscreenshare.discovery.provider.DiscoveryProviderListener;
import com.obby.android.localscreenshare.discovery.store.DeviceStore;
import com.obby.android.localscreenshare.discovery.store.DeviceStoreListener;
import com.obby.android.localscreenshare.identity.IdentityManager;
import com.obby.android.localscreenshare.identity.IdentityUnavailableException;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Process-wide discovery facade that publishes the LAN screen-stream servers it can reach.
 *
 * <p>The manager keeps the original public contract — subscribe, unsubscribe and receive an
 * {@link LssServiceInfo} snapshot — while the state itself lives in {@link DeviceStore}. An
 * {@link NsdDiscoveryProvider} feeds observations into the store, the store merges them with what
 * every other discovery channel reports, and this class projects the result onto the legacy list
 * the UI already renders. The activity therefore needs no change when the store or the NSD
 * hardening evolves.</p>
 *
 * <p><b>Threading.</b> All manager state is confined to the main thread: NSD callbacks are
 * marshaled by the provider, the store is main-thread confined and is driven from here, and
 * {@link LssServiceDiscoveryListener} callbacks are therefore main-thread callbacks. The public
 * methods must be called on the main thread. Listener notifications carry an unmodifiable snapshot
 * that is replaced, never mutated, on change.</p>
 *
 * <p><b>Lifecycle.</b> Discovery starts when the first listener subscribes with
 * {@link #discoverServices(LssServiceDiscoveryListener)} and stops when the last listener leaves
 * through {@link #stopServiceDiscovery(LssServiceDiscoveryListener)}. A one-second main-thread
 * tick drives the store TTL while discovery is running. Subscribing does not clear cached results:
 * a new listener immediately receives the current snapshot.</p>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LssServiceDiscoveryManager {
    private static final String TAG = "LssServiceDiscoveryManager";

    // Main-thread state: true while discovery is running.
    private boolean mIsDiscovering;

    // Main-thread snapshot; unmodifiable and replaced whenever the discovered set changes.
    @NonNull
    private List<LssServiceInfo> mServiceInfoList = Collections.emptyList();

    @NonNull
    private final DeviceStore mDeviceStore = new DeviceStore(TimeSource.MONOTONIC);

    @NonNull
    private final NsdDiscoveryProvider mDiscoveryProvider = new NsdDiscoveryProvider(App.get(),
        TimeSource.MONOTONIC, this::isSelfDeviceId);

    @NonNull
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // Copy-on-write so dispatch can iterate while a listener removes itself.
    @NonNull
    private final List<LssServiceDiscoveryListener> mServiceDiscoveryListeners = new CopyOnWriteArrayList<>();

    // Projects the store snapshot onto the legacy list and forwards changes to subscribers.
    @NonNull
    private final DeviceStoreListener mDeviceStoreListener = devices -> updateServiceInfoList();

    @NonNull
    private final DiscoveryProviderListener mDiscoveryProviderListener = new DiscoveryProviderListener() {
        @Override
        public void onObserved(@NonNull DeviceObservation observation) {
            mDeviceStore.onObserved(observation);
        }

        @Override
        public void onProviderError(@NonNull String reason) {
            Log.w(TAG, String.format("mDiscoveryProviderListener.onProviderError: %s", reason));
        }
    };

    // Re-arms itself while discovery is running and lets the store expire stale entries.
    @NonNull
    private final Runnable mTickRunnable = new Runnable() {
        @Override
        public void run() {
            mMainHandler.removeCallbacks(mTickRunnable);
            if (!mIsDiscovering) {
                return;
            }
            mDeviceStore.tick();
            mMainHandler.postDelayed(mTickRunnable, DeviceStore.TICK_INTERVAL_MS);
        }
    };

    @NonNull
    public static LssServiceDiscoveryManager get() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * Subscribes a listener and starts discovery if it was not running.
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
     * Unsubscribes a listener and stops discovery once no listener remains.
     *
     * <p>Must be called on the main thread. Stopping discovery drops the cached device store; the
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
     * Drops every discovered device after this installation's identity was replaced.
     *
     * <p>Must be called on the main thread, from the flow that called
     * {@link IdentityManager#reset()}. Discovery keeps running; entries reappear as peers are heard
     * again, so nothing observed under the old identity is silently reused under the new one.</p>
     */
    public void onIdentityReset() {
        Log.i(TAG, "onIdentityReset: clear discovered devices");
        mDeviceStore.clear();
    }

    /**
     * Starts the NSD provider, attaches the store listener and arms the TTL tick.
     *
     * <p>Must be called on the main thread.</p>
     */
    private void startDiscovery() {
        Log.i(TAG, "startDiscovery: start discovery");
        mDeviceStore.addListener(mDeviceStoreListener);
        mDiscoveryProvider.start(mDiscoveryProviderListener);
        mMainHandler.removeCallbacks(mTickRunnable);
        mMainHandler.postDelayed(mTickRunnable, DeviceStore.TICK_INTERVAL_MS);
    }

    /**
     * Stops discovery and drops cached devices. The resulting empty snapshot is dispatched while
     * the listener list is already empty, so no listener observes it.
     *
     * <p>Must be called on the main thread, after the last listener has been removed.</p>
     */
    private void stopDiscovery() {
        Log.i(TAG, "stopDiscovery: stop service discovery");
        mDiscoveryProvider.stop();
        mMainHandler.removeCallbacks(mTickRunnable);
        mDeviceStore.removeListener(mDeviceStoreListener);
        mDeviceStore.clear();
        updateServiceInfoList();
    }

    /**
     * Returns whether {@code id} is this installation's own device id.
     *
     * <p>Peers are filtered by identity, never by address: one device can appear on several
     * interfaces and the two stacks may use different addresses. When the identity cannot be
     * loaded, nothing is filtered — a duplicated self entry is preferable to hiding every peer.</p>
     */
    private boolean isSelfDeviceId(@NonNull final String id) {
        try {
            return Objects.equals(id, IdentityManager.get().getIdentity().getId());
        } catch (IdentityUnavailableException e) {
            Log.w(TAG, "isSelfDeviceId: device identity unavailable");
            return false;
        }
    }

    /**
     * Rebuilds the legacy snapshot and notifies listeners only when it differs from the previous
     * one. Equality relies on the value semantics of {@link LssServiceInfo}.
     */
    private void updateServiceInfoList() {
        final List<LssServiceInfo> serviceInfoList = mDeviceStore.legacySnapshot();
        if (Objects.equals(mServiceInfoList, serviceInfoList)) {
            Log.w(TAG, "updateServiceInfoList: service info list not changed");
            return;
        }

        mServiceInfoList = serviceInfoList;
        Log.i(TAG, String.format("updateServiceInfoList: service info list size is %d", mServiceInfoList.size()));

        mServiceDiscoveryListeners.forEach(listener -> listener.onServicesChanged(mServiceInfoList));
    }

    // Holds the process-wide instance; initialized on the first call to get().
    private static class InstanceHolder {
        private static final LssServiceDiscoveryManager INSTANCE = new LssServiceDiscoveryManager();
    }
}
