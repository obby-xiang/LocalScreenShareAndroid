package com.obby.android.localscreenshare.discovery;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.App;
import com.obby.android.localscreenshare.core.stream.TimeSource;
import com.obby.android.localscreenshare.discovery.model.DeviceRole;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.model.JoinPolicy;
import com.obby.android.localscreenshare.discovery.model.LssDeviceInfo;
import com.obby.android.localscreenshare.discovery.model.ShareState;
import com.obby.android.localscreenshare.discovery.nsd.NsdDiscoveryProvider;
import com.obby.android.localscreenshare.discovery.provider.DeviceObservation;
import com.obby.android.localscreenshare.discovery.provider.DiscoveryProviderListener;
import com.obby.android.localscreenshare.discovery.register.GrpcRegisterStub;
import com.obby.android.localscreenshare.discovery.register.RegisterClient;
import com.obby.android.localscreenshare.discovery.register.RegisterProtoMapper;
import com.obby.android.localscreenshare.discovery.store.DeviceStore;
import com.obby.android.localscreenshare.discovery.store.DeviceStoreListener;
import com.obby.android.localscreenshare.discovery.udp.AndroidMulticastSocketFactory;
import com.obby.android.localscreenshare.discovery.udp.MulticastConfig;
import com.obby.android.localscreenshare.discovery.udp.MulticastLockManager;
import com.obby.android.localscreenshare.discovery.udp.SocketMulticastTransport;
import com.obby.android.localscreenshare.discovery.udp.UdpMulticastDiscoveryProvider;
import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;
import com.obby.android.localscreenshare.identity.DeviceIdentity;
import com.obby.android.localscreenshare.identity.IdentityManager;
import com.obby.android.localscreenshare.identity.IdentityUnavailableException;
import com.obby.android.localscreenshare.support.Constants;
import com.obby.android.localscreenshare.support.DelayedScheduler;
import com.obby.android.localscreenshare.support.Preferences;
import com.obby.android.localscreenshare.utils.ThreadUtils;

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
 * {@link LssServiceInfo} snapshot — while the state itself lives in {@link DeviceStore}. Two
 * providers feed observations into the store in parallel: {@link NsdDiscoveryProvider} for mDNS
 * and {@link UdpMulticastDiscoveryProvider} for the multicast announcement channel. The store
 * merges what both report, and this class projects the result onto the legacy list the UI already
 * renders.</p>
 *
 * <p>The multicast provider also confirms peers through Register: only an accepted handshake
 * promotes a device to confirmed, so a received announcement alone never makes a device look
 * reachable. The store is exposed through {@link #deviceStore()} so the capture server can write
 * the peers it learns through its own Register handler into the same table.</p>
 *
 * <p><b>Threading.</b> All manager state is confined to the main thread: NSD and multicast
 * callbacks are marshaled by the providers, the store is main-thread confined and is driven from
 * here, and {@link LssServiceDiscoveryListener} callbacks are therefore main-thread callbacks. The
 * public methods must be called on the main thread. Listener notifications carry an unmodifiable
 * snapshot that is replaced, never mutated, on change.</p>
 *
 * <p><b>Lifecycle.</b> Discovery starts when the first listener subscribes with
 * {@link #discoverServices(LssServiceDiscoveryListener)} and stops when the last listener leaves
 * through {@link #stopServiceDiscovery(LssServiceDiscoveryListener)}. A one-second main-thread
 * tick drives the store TTL while discovery is running. Subscribing does not clear cached results:
 * a new listener immediately receives the current snapshot. The multicast group and port are read
 * when the provider starts, so a changed setting applies to the next discovery start.</p>
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
    private final RegisterClient mRegisterClient = new RegisterClient(new GrpcRegisterStub(),
        DelayedScheduler.newSingleThread("lss-register"), ThreadUtils.getMainThreadExecutor());

    @NonNull
    private final UdpMulticastDiscoveryProvider mMulticastProvider = new UdpMulticastDiscoveryProvider(
        () -> new SocketMulticastTransport(new AndroidMulticastSocketFactory(), multicastConfig(),
            DelayedScheduler.newSingleThread("lss-multicast-socket")),
        MulticastLockManager.get(), this::isSelfDeviceId, TimeSource.MONOTONIC,
        ThreadUtils.getMainThreadExecutor(), mRegisterClient, this::selfSummary,
        this::onConfirmed);

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
     * Returns the process-wide device store.
     *
     * <p>The capture server writes the peers it learns through Register into the same store, so a
     * device that dialled in is visible in the list without waiting for its next announcement.
     * Must be called on the main thread; the store itself is main-thread confined.</p>
     */
    @NonNull
    public DeviceStore deviceStore() {
        return mDeviceStore;
    }

    /**
     * Starts both discovery providers.
     *
     * <p>Must be called on the main thread.</p>
     */
    private void startDiscovery() {
        Log.i(TAG, "startDiscovery: start discovery");
        mDeviceStore.addListener(mDeviceStoreListener);
        mDiscoveryProvider.start(mDiscoveryProviderListener);
        mMulticastProvider.start(mDiscoveryProviderListener);
        mMainHandler.removeCallbacks(mTickRunnable);
        mMainHandler.postDelayed(mTickRunnable, DeviceStore.TICK_INTERVAL_MS);
    }

    /**
     * Stops both discovery providers and drops cached devices. The resulting empty snapshot is
     * dispatched while the listener list is already empty, so no listener observes it.
     *
     * <p>Must be called on the main thread, after the last listener has been removed.</p>
     */
    private void stopDiscovery() {
        Log.i(TAG, "stopDiscovery: stop service discovery");
        mDiscoveryProvider.stop();
        mMulticastProvider.stop();
        mMainHandler.removeCallbacks(mTickRunnable);
        mDeviceStore.removeListener(mDeviceStoreListener);
        mDeviceStore.clear();
        updateServiceInfoList();
    }

    /** Reads the multicast configuration for one provider start. */
    @NonNull
    private static MulticastConfig multicastConfig() {
        return new MulticastConfig(Preferences.get().getMulticastGroup(),
            Preferences.get().getMulticastPort(), Constants.MULTICAST_TTL,
            Constants.MULTICAST_MAX_INTERFACES);
    }

    /**
     * Builds this device's Register summary, or null when the identity is unavailable.
     *
     * <p>A viewer device has no server address, so the summary carries an empty address list; the
     * responding server still learns the caller's identity from it.</p>
     */
    @Nullable
    private DeviceSummary selfSummary() {
        try {
            final DeviceIdentity identity = IdentityManager.get().getIdentity();
            final LssDeviceInfo self = LssDeviceInfo.builder()
                .id(identity.getId())
                .fingerprint(identity.getFingerprint())
                .role(DeviceRole.BOTH)
                .state(ShareState.IDLE)
                .join(JoinPolicy.UNKNOWN)
                .secure(Preferences.get().isProjectionSecure())
                .capabilities(Constants.DEVICE_CAPABILITY_H264 | Constants.DEVICE_CAPABILITY_JPEG)
                .metadataVersion(1)
                .build();
            return RegisterProtoMapper.toSummary(self);
        } catch (IdentityUnavailableException e) {
            Log.w(TAG, "selfSummary: device identity unavailable");
            return null;
        }
    }

    /**
     * Applies a successful Register to the store: the peer is written with the fingerprint the
     * handshake returned and then confirmed at the announced address.
     *
     * <p>Runs on the main thread through the register client's callback executor.</p>
     */
    private void onConfirmed(@NonNull final DeviceSummary peer, @NonNull final String host,
        final int port) {
        if (port <= 0) {
            return;
        }
        mDeviceStore.onObserved(RegisterProtoMapper.toObservation(peer, DiscoveryChannel.MULTICAST,
            host, port, null));
        mDeviceStore.confirm(peer.getDeviceId(), DiscoveryChannel.MULTICAST, host, port);
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
