package com.obby.android.localscreenshare.discovery;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.App;
import com.obby.android.localscreenshare.core.util.TimeSource;
import com.obby.android.localscreenshare.discovery.favorite.FavoriteDevice;
import com.obby.android.localscreenshare.discovery.favorite.FavoriteStore;
import com.obby.android.localscreenshare.discovery.legacy.LegacyServiceDiscoveryListener;
import com.obby.android.localscreenshare.discovery.legacy.LegacyServiceInfo;
import com.obby.android.localscreenshare.discovery.manual.ManualAddressProvider;
import com.obby.android.localscreenshare.discovery.model.DeviceAddress;
import com.obby.android.localscreenshare.discovery.model.DeviceRole;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.model.JoinPolicy;
import com.obby.android.localscreenshare.discovery.model.DeviceInfo;
import com.obby.android.localscreenshare.discovery.model.ShareState;
import com.obby.android.localscreenshare.discovery.nsd.NsdDiscoveryProvider;
import com.obby.android.localscreenshare.discovery.provider.DeviceObservation;
import com.obby.android.localscreenshare.discovery.provider.DiscoveryProviderListener;
import com.obby.android.localscreenshare.discovery.register.GrpcRegisterStub;
import com.obby.android.localscreenshare.discovery.register.RegisterClient;
import com.obby.android.localscreenshare.discovery.register.RegisterConstants;
import com.obby.android.localscreenshare.discovery.register.RegisterProtoMapper;
import com.obby.android.localscreenshare.discovery.scan.AddressScanner;
import com.obby.android.localscreenshare.discovery.scan.AndroidInterfaceEnumerator;
import com.obby.android.localscreenshare.discovery.scan.RegisterAddressProbe;
import com.obby.android.localscreenshare.discovery.scan.ScanConstants;
import com.obby.android.localscreenshare.discovery.scan.ScanCoordinator;
import com.obby.android.localscreenshare.discovery.store.DeviceStore;
import com.obby.android.localscreenshare.discovery.store.DeviceStoreListener;
import com.obby.android.localscreenshare.discovery.udp.MulticastConfig;
import com.obby.android.localscreenshare.discovery.udp.MulticastConstants;
import com.obby.android.localscreenshare.discovery.udp.MulticastLockManager;
import com.obby.android.localscreenshare.discovery.udp.PerInterfaceMulticastSocketFactory;
import com.obby.android.localscreenshare.discovery.udp.SocketMulticastTransport;
import com.obby.android.localscreenshare.discovery.udp.UdpMulticastDiscoveryProvider;
import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;
import com.obby.android.localscreenshare.identity.DeviceIdentity;
import com.obby.android.localscreenshare.identity.IdentityManager;
import com.obby.android.localscreenshare.identity.IdentityUnavailableException;
import com.obby.android.localscreenshare.settings.DiscoverySettings;
import com.obby.android.localscreenshare.settings.Preferences;
import com.obby.android.localscreenshare.support.DelayedScheduler;
import com.obby.android.localscreenshare.support.ThreadUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Process-wide discovery facade that publishes the LAN screen-stream servers it can reach.
 *
 * <p>The manager keeps the original public contract — subscribe, unsubscribe and receive an
 * {@link LegacyServiceInfo} snapshot — and adds the store contract the unified list renders:
 * {@link #addDeviceListener(DeviceStoreListener)} delivers the immutable {@link DeviceInfo}
 * snapshot. All state lives in {@link DeviceStore}. Three sources feed observations into the store
 * in parallel: {@link NsdDiscoveryProvider} for mDNS, {@link UdpMulticastDiscoveryProvider} for the
 * multicast announcement channel, and {@link ManualAddressProvider} for favorites and addresses
 * the user typed. The store merges what they report.</p>
 *
 * <p>The multicast provider also confirms peers through Register: only an accepted handshake
 * promotes a device to confirmed, so a received announcement alone never makes a device look
 * reachable. An address confirmed within
 * {@link RegisterConstants#REGISTER_RECONFIRM_INTERVAL_MS} is not confirmed again, which keeps heartbeat
 * traffic below the server's per-address quota. The store is exposed through
 * {@link #getDeviceStore()} so the capture server can write the peers it learns through its own
 * Register handler into the same table.</p>
 *
 * <p><b>Fallbacks.</b> {@link ScanCoordinator} waits one grace window for announcements and
 * favorite probes; only a window that ends with no confirmed device escalates to an interface
 * scan. Favorites are persisted in {@link FavoriteStore}, seeded into the store at start so an
 * unreachable favorite keeps its alias and marker, and probed before anything else. The scan never
 * confirms a device by itself: its hits go through the same confirm path as a handshake.</p>
 *
 * <p><b>Threading.</b> All manager state is confined to the main thread: NSD and multicast
 * callbacks are marshaled by the providers, the store is main-thread confined and is driven from
 * here, and {@link LegacyServiceDiscoveryListener} callbacks are therefore main-thread callbacks. The
 * public methods must be called on the main thread. Listener notifications carry an unmodifiable
 * snapshot that is replaced, never mutated, on change.</p>
 *
 * <p><b>Lifecycle.</b> Discovery starts when the first listener of either kind subscribes and stops
 * when the last one leaves. A one-second main-thread tick drives the store TTL while discovery is
 * running. Subscribing does not clear cached results: a new listener immediately receives the
 * current snapshot. Discovery settings are read when a provider starts, and a change restarts the
 * running providers after a short debounce, so a new group, port, refresh preset or compatibility
 * mode applies without restarting a share session.</p>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class DiscoveryManager {
    private static final String TAG = "DiscoveryManager";

    // Connectivity callbacks often arrive in bursts; one restart per burst is enough.
    private static final long NETWORK_CHANGE_DEBOUNCE_MS = 500L;

    // Settings writes also arrive in bursts, because one picker change writes several keys.
    private static final long SETTINGS_DEBOUNCE_MS = 200L;

    // Manually added addresses are re-probed on this cadence so they stay confirmed while reachable.
    private static final long MANUAL_REPROBE_INTERVAL_MS = 15_000L;

    // Preference keys that change how discovery runs and therefore restart the providers.
    @NonNull
    private static final List<String> DISCOVERY_KEYS = Arrays.asList(
        Preferences.KEY_MULTICAST_ANNOUNCE_ENABLED, Preferences.KEY_MULTICAST_GROUP,
        Preferences.KEY_MULTICAST_PORT, Preferences.KEY_DISCOVERY_REFRESH_TIER,
        Preferences.KEY_DISCOVERY_VISIBILITY, Preferences.KEY_DISCOVERY_VISIBILITY_UNTIL,
        Preferences.KEY_DISCOVERY_COMPAT_ONLY);

    // Main-thread state: true while discovery is running.
    private boolean mIsDiscovering;

    // Main-thread snapshot; unmodifiable and replaced whenever the discovered set changes.
    @NonNull
    private List<LegacyServiceInfo> mServiceInfoList = Collections.emptyList();

    @NonNull
    private final DeviceStore mDeviceStore = new DeviceStore(TimeSource.MONOTONIC);

    @NonNull
    private final RegisterClient mRegisterClient = new RegisterClient(new GrpcRegisterStub(),
        DelayedScheduler.newSingleThread("lss-register"), ThreadUtils.getMainThreadExecutor());

    @NonNull
    private final UdpMulticastDiscoveryProvider mMulticastProvider = new UdpMulticastDiscoveryProvider(
        () -> new SocketMulticastTransport(new PerInterfaceMulticastSocketFactory(), multicastConfig(),
            DelayedScheduler.newSingleThread("lss-multicast-socket")),
        MulticastLockManager.get(), this::isSelfDeviceId, TimeSource.MONOTONIC,
        ThreadUtils.getMainThreadExecutor(), mRegisterClient, this::selfSummary,
        this::onConfirmed,
        (host, port) -> mDeviceStore.isConfirmedWithin(host, port, RegisterConstants.REGISTER_RECONFIRM_INTERVAL_MS));

    // Recreated on every start because the discovered service types depend on the settings.
    @Nullable
    private NsdDiscoveryProvider mDiscoveryProvider;

    @NonNull
    private final FavoriteStore mFavoriteStore = FavoriteStore.get();

    // Scans and manual probes are single-shot: they bypass the retrying RegisterClient, because a
    // scan must not multiply the traffic of a full subnet.
    @NonNull
    private final AddressScanner mAddressScanner = new AddressScanner(
        new RegisterAddressProbe(new GrpcRegisterStub(), this::selfSummary, DiscoveryChannel.SCAN),
        ScanConstants.SCAN_MAX_CONCURRENCY, ScanConstants.SCAN_BUDGET_MS, TimeSource.MONOTONIC);

    @NonNull
    private final ScanCoordinator mScanCoordinator = new ScanCoordinator(
        DelayedScheduler.newSingleThread("lss-scan-scheduler"),
        new AndroidInterfaceEnumerator(App.get()), mAddressScanner,
        scanExecutor(), ThreadUtils.getMainThreadExecutor(),
        this::confirmedDeviceCount, () -> Preferences.get().getServerPort());

    @NonNull
    private final ManualAddressProvider mManualAddressProvider = new ManualAddressProvider(
        new RegisterAddressProbe(new GrpcRegisterStub(), this::selfSummary, DiscoveryChannel.MANUAL),
        mFavoriteStore, ThreadUtils.getMainThreadExecutor(), this::onConfirmed,
        ScanConstants.MANUAL_PROBE_TIMEOUT_MS);

    @NonNull
    private final ScanCoordinator.Listener mScanListener = new ScanCoordinator.Listener() {
        @Override
        public void onStateChanged(@NonNull final ScanCoordinator.State state) {
            Log.i(TAG, String.format("mScanListener.onStateChanged: state = %s", state));
        }

        @Override
        public void onScanCompleted(@NonNull final AddressScanner.ScanResult result) {
            Log.i(TAG, String.format("mScanListener.onScanCompleted: scanned = %d, confirmed = %d, "
                    + "cancelled = %b, partial = %b", result.getScanned(), result.getConfirmedCount(),
                result.isCancelled(), result.isPartial()));
            for (final AddressScanner.ScanHit hit : result.getConfirmed()) {
                onConfirmed(DiscoveryChannel.SCAN, hit.getSummary(), hit.getHost(), hit.getPort());
            }
        }
    };

    @Nullable
    private ConnectivityManager.NetworkCallback mNetworkCallback;

    @NonNull
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // Copy-on-write so dispatch can iterate while a listener removes itself.
    @NonNull
    private final List<LegacyServiceDiscoveryListener> mServiceDiscoveryListeners = new CopyOnWriteArrayList<>();

    // Consumers of the store snapshot itself; the activity renders the list from these.
    @NonNull
    private final List<DeviceStoreListener> mDeviceListeners = new CopyOnWriteArrayList<>();

    // Projects the store snapshot onto the legacy list and forwards it to device listeners.
    @NonNull
    private final DeviceStoreListener mDeviceStoreListener = devices -> {
        updateServiceInfoList();
        for (final DeviceStoreListener listener : mDeviceListeners) {
            listener.onDevicesChanged(devices);
        }
    };

    @NonNull
    private final Runnable mSettingsChangedRunnable = new Runnable() {
        @Override
        public void run() {
            mMainHandler.removeCallbacks(mSettingsChangedRunnable);
            restartDiscoveryProviders();
        }
    };

    // Settings are applied by restarting the providers; consecutive writes are collapsed.
    @NonNull
    private final Preferences.Observer mPreferencesObserver = key -> {
        if (key == null || DISCOVERY_KEYS.contains(key)) {
            mMainHandler.removeCallbacks(mSettingsChangedRunnable);
            mMainHandler.postDelayed(mSettingsChangedRunnable, SETTINGS_DEBOUNCE_MS);
        }
    };

    @NonNull
    private final DiscoveryProviderListener mDiscoveryProviderListener = new DiscoveryProviderListener() {
        @Override
        public void onObserved(@NonNull final DeviceObservation observation) {
            mDeviceStore.onObserved(observation);
            syncFavorite(observation);
        }

        @Override
        public void onProviderError(@NonNull String reason) {
            Log.w(TAG, String.format("mDiscoveryProviderListener.onProviderError: %s", reason));
        }
    };

    // Deferred scan when the list is still empty after the initial window.
    @NonNull
    private final Runnable mEmptyListScanRunnable = new Runnable() {
        @Override
        public void run() {
            mMainHandler.removeCallbacks(mEmptyListScanRunnable);
            if (mIsDiscovering && mDeviceStore.snapshot().isEmpty()
                && Preferences.get().getDiscoverySettings().isScanEnabled()) {
                mScanCoordinator.start(ScanCoordinator.Trigger.EMPTY_LIST);
            }
        }
    };

    // Connectivity bursts produce several callbacks; one restart per burst is enough.
    @NonNull
    private final Runnable mNetworkChangedRunnable = new Runnable() {
        @Override
        public void run() {
            mMainHandler.removeCallbacks(mNetworkChangedRunnable);
            if (mIsDiscovering) {
                mScanCoordinator.start(ScanCoordinator.Trigger.NETWORK_CHANGED);
            }
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

    // Re-arms itself while discovery is running and re-probes manually added addresses.
    @NonNull
    private final Runnable mManualReprobeRunnable = new Runnable() {
        @Override
        public void run() {
            mMainHandler.removeCallbacks(mManualReprobeRunnable);
            if (!mIsDiscovering) {
                return;
            }
            mManualAddressProvider.probeManualTargets();
            mMainHandler.postDelayed(mManualReprobeRunnable, MANUAL_REPROBE_INTERVAL_MS);
        }
    };

    @NonNull
    public static DiscoveryManager get() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * Subscribes a listener and starts discovery if it was not running.
     *
     * <p>Must be called on the main thread. A listener that is already subscribed is ignored.
     * Otherwise the listener receives the current snapshot synchronously before this method
     * returns, whether or not discovery was just started.</p>
     */
    public void startDiscovery(@NonNull final LegacyServiceDiscoveryListener listener) {
        Log.i(TAG, "startDiscovery: discover services");

        if (mServiceDiscoveryListeners.contains(listener)) {
            Log.w(TAG, "startDiscovery: listener already exists");
            return;
        }

        mServiceDiscoveryListeners.add(listener);
        if (!mIsDiscovering) {
            mIsDiscovering = true;
            startDiscoveryProviders();
        }

        listener.onServicesChanged(mServiceInfoList);
    }

    /**
     * Subscribes a listener that receives the device store snapshot, and starts discovery if it
     * was not running.
     *
     * <p>This is the rendering contract introduced with the unified list: the listener gets the
     * immutable {@link DeviceInfo} snapshot, which carries identity, addresses, presence and
     * status. Must be called on the main thread; the current snapshot is delivered synchronously
     * before this method returns. A duplicate subscription is ignored.</p>
     */
    public void addDeviceListener(@NonNull final DeviceStoreListener listener) {
        if (mDeviceListeners.contains(listener)) {
            return;
        }
        mDeviceListeners.add(listener);
        if (!mIsDiscovering) {
            mIsDiscovering = true;
            startDiscoveryProviders();
        }
        listener.onDevicesChanged(mDeviceStore.snapshot());
    }

    /**
     * Unsubscribes a device listener and stops discovery once no listener of either kind remains.
     *
     * <p>Must be called on the main thread.</p>
     */
    public void removeDeviceListener(@NonNull final DeviceStoreListener listener) {
        if (!mDeviceListeners.remove(listener)) {
            return;
        }
        if (mServiceDiscoveryListeners.isEmpty() && mDeviceListeners.isEmpty() && mIsDiscovering) {
            mIsDiscovering = false;
            stopDiscoveryProviders();
        }
    }

    /**
     * Unsubscribes a listener and stops discovery once no listener remains.
     *
     * <p>Must be called on the main thread. Stopping discovery drops the cached device store; the
     * resulting empty snapshot is not broadcast because the listener list is empty at that
     * point.</p>
     */
    public void stopDiscovery(@NonNull final LegacyServiceDiscoveryListener listener) {
        Log.i(TAG, "stopDiscovery: stop service discovery");

        if (!mServiceDiscoveryListeners.remove(listener)) {
            Log.w(TAG, "stopDiscovery: listener does not exist");
            return;
        }

        if (mServiceDiscoveryListeners.isEmpty() && mDeviceListeners.isEmpty() && mIsDiscovering) {
            mIsDiscovering = false;
            stopDiscoveryProviders();
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
    public DeviceStore getDeviceStore() {
        return mDeviceStore;
    }

    /**
     * Returns the process-wide favorite store.
     *
     * <p>The activity writes favorites through {@link #setFavorite(String, boolean)} so the store
     * is updated in the same step; the getter exists for rendering the favorite marker.</p>
     */
    @NonNull
    public FavoriteStore getFavoriteStore() {
        return mFavoriteStore;
    }

    /**
     * Adds or removes a favorite and updates the device list in one step.
     *
     * <p>Adding a device that the store knows records its current address and fingerprint. Must be
     * called on the main thread.</p>
     *
     * @return true when the device is a favorite afterwards
     */
    public boolean setFavorite(@NonNull final String id, final boolean isFavorite) {
        if (!isFavorite) {
            mFavoriteStore.remove(id);
            mDeviceStore.setFavorite(id, false);
            return false;
        }

        FavoriteDevice existing = mFavoriteStore.find(id);
        if (existing == null) {
            final DeviceInfo device = mDeviceStore.device(id);
            final DeviceAddress address = device == null ? null : device.primaryAddress();
            if (device == null || address == null) {
                return false;
            }
            existing = FavoriteDevice.create(id, device.getFingerprint(), address.getHost(),
                address.getPort(), device.getName(), false);
            mFavoriteStore.add(existing);
        }
        mDeviceStore.setFavorite(id, true);
        mDeviceStore.setIdentityChanged(id, existing.isIdentityChanged());
        return true;
    }

    /**
     * Returns whether {@code id} is a favorite.
     */
    public boolean isFavorite(@NonNull final String id) {
        return mFavoriteStore.isFavorite(id);
    }

    /**
     * Accepts a changed identity of a favorite after the user confirmed it.
     *
     * <p>The favorite and the store entry are rebound to the new fingerprint in one step, so the
     * warning disappears and the next comparison uses the accepted key.</p>
     *
     * @return false when the device is not a favorite
     */
    public boolean confirmFavoriteIdentity(@NonNull final String id,
        @Nullable final String fingerprint) {
        final boolean confirmed = mFavoriteStore.confirmIdentity(id, fingerprint);
        if (confirmed) {
            mDeviceStore.confirmIdentity(id, fingerprint);
        }
        return confirmed;
    }

    /**
     * Probes an address typed by the user and, when it answers, adds it to the list.
     *
     * <p>Must be called on the main thread. The callback is delivered on the main thread; a
     * successful probe is confirmed through the same path as a discovered device.</p>
     *
     * @param input text such as {@code 192.168.1.7:8080}, {@code [fe80::1]:8080} or a bare address
     * @param callback outcome callback
     * @return false when the input could not be parsed
     */
    public boolean probeManualAddress(@NonNull final String input,
        @NonNull final ManualAddressProvider.ProbeCallback callback) {
        return mManualAddressProvider.probeManual(input, Preferences.get().getServerPort(),
            new ManualAddressProvider.ProbeCallback() {
                @Override
                public void onConfirmed(@NonNull final String host, final int port,
                    @NonNull final DeviceSummary peer) {
                    callback.onConfirmed(host, port, peer);
                }

                @Override
                public void onFailed(@NonNull final String reason) {
                    Log.w(TAG, String.format("probeManualAddress: probe failed, input = %s, reason = %s",
                        input, reason));
                    callback.onFailed(reason);
                }
            });
    }

    /**
     * Requests another scan after the user asked to search again.
     *
     * <p>Runs only while discovery is active; a running grace window or scan coalesces the
     * request.</p>
     */
    public void refreshDiscovery() {
        if (mIsDiscovering && Preferences.get().getDiscoverySettings().isScanEnabled()) {
            mScanCoordinator.start(ScanCoordinator.Trigger.MANUAL_REFRESH);
        }
    }

    /**
     * Starts both discovery providers, the favorite probes and the scan coordinator.
     *
     * <p>Favorites are seeded into the store first, so a favorite that is not reachable still
     * appears with its alias and the favorite marker. Must be called on the main thread.</p>
     */
    private void startDiscoveryProviders() {
        Log.i(TAG, "startDiscoveryProviders: start discovery");
        final DiscoverySettings settings = Preferences.get().getDiscoverySettings();
        migrateFavorites();
        seedFavorites();
        mDeviceStore.addListener(mDeviceStoreListener);
        mRegisterClient.setTimeoutMs(settings.getRefreshTier().getRegisterTimeoutMs());
        Preferences.get().addObserver(mPreferencesObserver);
        startProviders(settings);
        mManualAddressProvider.start(mDiscoveryProviderListener);
        mScanCoordinator.setListener(mScanListener);
        if (settings.isScanEnabled()) {
            mScanCoordinator.start(ScanCoordinator.Trigger.DISCOVERY_START);
        }
        mMainHandler.removeCallbacks(mEmptyListScanRunnable);
        mMainHandler.postDelayed(mEmptyListScanRunnable, ScanConstants.DISCOVERY_EMPTY_MANUAL_HINT_MS);
        registerNetworkCallback();
        mMainHandler.removeCallbacks(mTickRunnable);
        mMainHandler.postDelayed(mTickRunnable, DeviceStore.TICK_INTERVAL_MS);
        mMainHandler.removeCallbacks(mManualReprobeRunnable);
        mMainHandler.postDelayed(mManualReprobeRunnable, MANUAL_REPROBE_INTERVAL_MS);
    }

    /**
     * Starts the discovery providers that the settings allow.
     *
     * <p>The NSD provider is recreated on every start because compatibility mode changes the set
     * of discovered service types. The multicast provider is a field because its transport
     * factory reads the group and port each time it starts; when the channel is disabled or
     * compatibility mode is on, it is not started at all.</p>
     */
    private void startProviders(@NonNull final DiscoverySettings settings) {
        final NsdDiscoveryProvider discoveryProvider = new NsdDiscoveryProvider(App.get(),
            TimeSource.MONOTONIC, this::isSelfDeviceId, settings.nsdServiceTypes());
        mDiscoveryProvider = discoveryProvider;
        discoveryProvider.start(mDiscoveryProviderListener);
        if (settings.isMulticastChannelEnabled()) {
            mMulticastProvider.start(mDiscoveryProviderListener);
        }
    }

    /**
     * Applies changed discovery settings to the running providers.
     *
     * <p>Runs on the main thread after a short debounce. The store is kept: the providers restart,
     * the devices that were already confirmed stay visible until their TTL, and new observations
     * come in under the new configuration. A share session is not restarted; the announcement
     * side of the settings is applied by the capture service's own observer.</p>
     */
    private void restartDiscoveryProviders() {
        if (!mIsDiscovering) {
            return;
        }
        Log.i(TAG, "restartDiscoveryProviders: restart discovery providers");
        final DiscoverySettings settings = Preferences.get().getDiscoverySettings();
        mRegisterClient.setTimeoutMs(settings.getRefreshTier().getRegisterTimeoutMs());

        final NsdDiscoveryProvider discoveryProvider = mDiscoveryProvider;
        if (discoveryProvider != null) {
            discoveryProvider.stop();
            mDiscoveryProvider = null;
        }
        mMulticastProvider.stop();
        mScanCoordinator.cancel();
        startProviders(settings);
    }

    /**
     * Stops both discovery providers and drops cached devices. The resulting empty snapshot is
     * dispatched while the listener list is already empty, so no listener observes it.
     *
     * <p>Must be called on the main thread, after the last listener has been removed.</p>
     */
    private void stopDiscoveryProviders() {
        Log.i(TAG, "stopDiscoveryProviders: stop service discovery");
        mScanCoordinator.cancel();
        mManualAddressProvider.stop();
        unregisterNetworkCallback();
        final NsdDiscoveryProvider discoveryProvider = mDiscoveryProvider;
        if (discoveryProvider != null) {
            discoveryProvider.stop();
            mDiscoveryProvider = null;
        }
        mMulticastProvider.stop();
        Preferences.get().removeObserver(mPreferencesObserver);
        mMainHandler.removeCallbacks(mSettingsChangedRunnable);
        mMainHandler.removeCallbacks(mTickRunnable);
        mMainHandler.removeCallbacks(mManualReprobeRunnable);
        mMainHandler.removeCallbacks(mEmptyListScanRunnable);
        mMainHandler.removeCallbacks(mNetworkChangedRunnable);
        mDeviceStore.removeListener(mDeviceStoreListener);
        mDeviceStore.clear();
        updateServiceInfoList();
    }

    /**
     * Reads the multicast configuration for one provider start.
     */
    @NonNull
    private static MulticastConfig multicastConfig() {
        return new MulticastConfig(Preferences.get().getMulticastGroup(),
            Preferences.get().getMulticastPort(), MulticastConstants.MULTICAST_TTL,
            MulticastConstants.MULTICAST_MAX_INTERFACES);
    }

    /**
     * Returns the executor that runs a scan.
     *
     * <p>A scan blocks for the whole budget, so it gets its own daemon thread instead of occupying
     * a shared pool thread for up to eight seconds per interface.</p>
     */
    @NonNull
    private static Executor scanExecutor() {
        return ScanExecutorHolder.INSTANCE;
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
            final DeviceInfo self = DeviceInfo.builder()
                .id(identity.getId())
                .fingerprint(identity.getFingerprint())
                .role(DeviceRole.BOTH)
                .state(ShareState.IDLE)
                .join(JoinPolicy.UNKNOWN)
                .secure(Preferences.get().isProjectionSecure())
                .capabilities(Preferences.get().getDiscoverySettings().capabilities())
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
        onConfirmed(DiscoveryChannel.MULTICAST, peer, host, port);
    }

    /**
     * Applies a successful probe to the store and refreshes the favorite that owns the device.
     *
     * <p>Kept as a separate overload because every successful path — announcement, scan, favorite
     * probe, manual entry — ends here, and the channel decides which address the confirmation
     * belongs to.</p>
     */
    private void onConfirmed(@NonNull final DiscoveryChannel channel,
        @NonNull final DeviceSummary peer, @NonNull final String host, final int port) {
        final String id = peer.getDeviceId();
        if (port <= 0 || id.trim().isEmpty()) {
            return;
        }
        mDeviceStore.onObserved(RegisterProtoMapper.toObservation(peer, channel, host, port, null));
        mDeviceStore.confirm(id, channel, host, port);
        if (channel == DiscoveryChannel.MANUAL) {
            // A manually added address must not silently disappear once its one-shot probe's TTL
            // elapses; pin it so it degrades to unreachable and is re-probed instead of removed.
            mDeviceStore.setPinned(id, true);
        }
        mFavoriteStore.sync(id, emptyToNull(peer.getFingerprint()),
            emptyToNull(peer.getName()), host, port);
        applyFavoriteToStore(id);
    }

    /**
     * Refreshes the favorite label and flags of a device from an observation that is not a
     * confirmation.
     *
     * <p>An announcement carries the advertised name, so a rename reaches the favorite here even
     * before the next successful handshake. The address is only recorded when the observation
     * carries one.</p>
     */
    private void syncFavorite(@NonNull final DeviceObservation observation) {
        if (observation.isLost() || observation.isGoodbye()) {
            return;
        }
        if (!observation.hasIdentity() || !observation.hasAddress()) {
            return;
        }
        final FavoriteStore.SyncOutcome outcome = mFavoriteStore.sync(observation.getId(),
            observation.getFingerprint(), observation.getName(), observation.getHost(),
            observation.getPort());
        if (outcome != FavoriteStore.SyncOutcome.NOT_FAVORITE) {
            applyFavoriteToStore(observation.getId());
        }
    }

    /**
     * Pushes the persisted favorite label onto the store entry.
     *
     * <p>The store only knows what channels reported; a user alias or an identity warning is
     * favorite state and has to be applied explicitly. A null alias leaves the advertised name in
     * place.</p>
     */
    private void applyFavoriteToStore(@NonNull final String id) {
        final FavoriteDevice favorite = mFavoriteStore.find(id);
        if (favorite == null) {
            return;
        }
        mDeviceStore.setFavorite(id, true);
        mDeviceStore.setIdentityChanged(id, favorite.isIdentityChanged());
        if (favorite.getAlias() != null && favorite.hasAddress()) {
            mDeviceStore.onObserved(DeviceObservation.builder()
                .source(DiscoveryChannel.FAVORITE)
                .id(id)
                .name(favorite.getAlias())
                .host(favorite.getHost())
                .port(favorite.getPort())
                .build());
        }
    }

    /**
     * Imports the pre-identity favorite document once; a no-op when there is none.
     */
    private void migrateFavorites() {
        final int imported = mFavoriteStore.migrateLegacy();
        if (imported > 0) {
            Log.i(TAG, String.format("migrateFavorites: imported %d legacy favorites", imported));
        }
    }

    /**
     * Seeds persisted favorites into the store.
     *
     * <p>A favorite that is not reachable has no channel to report it, so without seeding it would
     * disappear from the list even though the user saved it. Seeded entries are favorites, which
     * makes the store keep them as {@code UNREACHABLE} instead of expiring them; the favorite probe
     * then promotes them to confirmed when the device answers.</p>
     */
    private void seedFavorites() {
        for (final FavoriteDevice favorite : mFavoriteStore.probeTargets()) {
            mDeviceStore.onObserved(DeviceObservation.builder()
                .source(DiscoveryChannel.FAVORITE)
                .id(favorite.getId())
                .fingerprint(favorite.getFingerprint())
                .name(favorite.getAlias())
                .host(favorite.getHost())
                .port(favorite.getPort())
                .build());
            mDeviceStore.setFavorite(favorite.getId(), true);
            mDeviceStore.setIdentityChanged(favorite.getId(), favorite.isIdentityChanged());
        }
    }

    /**
     * Counts the devices with an application-level confirmation, used by the scan grace.
     */
    private int confirmedDeviceCount() {
        int confirmed = 0;
        for (final DeviceInfo device : mDeviceStore.snapshot()) {
            if (device.isConfirmed()) {
                confirmed++;
            }
        }
        return confirmed;
    }

    /**
     * Watches connectivity so a scan is re-run when the interface set changed.
     *
     * <p>Callbacks arrive on a connectivity thread; they only schedule a debounced restart on the
     * main thread. Registering twice is ignored because the previous callback is unregistered
     * first.</p>
     */
    private void registerNetworkCallback() {
        final ConnectivityManager connectivityManager =
            App.get().getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) {
            return;
        }
        unregisterNetworkCallback();
        final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull final Network network) {
                onNetworkChanged();
            }

            @Override
            public void onLost(@NonNull final Network network) {
                onNetworkChanged();
            }

            @Override
            public void onCapabilitiesChanged(@NonNull final Network network,
                @NonNull final NetworkCapabilities capabilities) {
                onNetworkChanged();
            }
        };
        try {
            connectivityManager.registerDefaultNetworkCallback(callback);
            mNetworkCallback = callback;
        } catch (RuntimeException e) {
            Log.w(TAG, "registerNetworkCallback: unable to register", e);
        }
    }

    private void unregisterNetworkCallback() {
        final ConnectivityManager.NetworkCallback callback = mNetworkCallback;
        if (callback == null) {
            return;
        }
        mNetworkCallback = null;
        final ConnectivityManager connectivityManager =
            App.get().getSystemService(ConnectivityManager.class);
        try {
            if (connectivityManager != null) {
                connectivityManager.unregisterNetworkCallback(callback);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "unregisterNetworkCallback: unable to unregister", e);
        }
    }

    private void onNetworkChanged() {
        mMainHandler.removeCallbacks(mNetworkChangedRunnable);
        mMainHandler.postDelayed(mNetworkChangedRunnable, NETWORK_CHANGE_DEBOUNCE_MS);
    }

    @Nullable
    private static String emptyToNull(@Nullable final String value) {
        return value == null || value.trim().isEmpty() ? null : value;
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
     * one. Equality relies on the value semantics of {@link LegacyServiceInfo}.
     */
    private void updateServiceInfoList() {
        final List<LegacyServiceInfo> serviceInfoList = mDeviceStore.legacySnapshot();
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
        private static final DiscoveryManager INSTANCE = new DiscoveryManager();
    }

    // Holds the single-thread scan executor; created on the first scan request.
    private static class ScanExecutorHolder {
        private static final ExecutorService INSTANCE = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "lss-scan");
            thread.setDaemon(true);
            return thread;
        });
    }
}
