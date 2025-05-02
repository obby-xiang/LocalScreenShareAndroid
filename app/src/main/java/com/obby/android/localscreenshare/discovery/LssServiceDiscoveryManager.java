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

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LssServiceDiscoveryManager {
    private static final String TAG = "LssServiceDiscoveryManager";

    private boolean mIsDiscovering;

    @NonNull
    private List<LssServiceInfo> mServiceInfoList = Collections.emptyList();

    @NonNull
    private final NsdManager mNsdManager = App.get().getSystemService(NsdManager.class);

    @NonNull
    private final Map<String, NsdServiceInfo> mNsdServiceInfoMap = new ConcurrentHashMap<>();

    @NonNull
    private final Map<String, LssServiceInfo> mServiceInfoMap = new ConcurrentHashMap<>();

    @NonNull
    private final List<LssServiceDiscoveryListener> mServiceDiscoveryListeners = new CopyOnWriteArrayList<>();

    @NonNull
    private final NsdManager.DiscoveryListener mNsdDiscoveryListener =
        new NsdDiscoveryListenerWrapper(new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, String.format("mNsdDiscoveryListener.onStartDiscoveryFailed: start discovery failed"
                    + ", serviceType = %s, errorCode = %d", serviceType, errorCode));
                if (mIsDiscovering) {
                    Log.i(TAG, "mNsdDiscoveryListener.onStartDiscoveryFailed: restart discovery");
                    stopDiscovery();
                    startDiscovery();
                }
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
                if (mIsDiscovering) {
                    Log.i(TAG, "mNsdDiscoveryListener.onDiscoveryStopped: restart discovery");
                    stopDiscovery();
                    startDiscovery();
                }
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

                NsdUtils.resolveService(mNsdManager, serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                        if (!mNsdServiceInfoMap.containsKey(serviceName)) {
                            return;
                        }
                        Log.e(TAG, String.format("mNsdDiscoveryListener.onServiceFound: service resolve failed"
                            + ", serviceInfo = %s, errorCode = %d", serviceInfo, errorCode));
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo serviceInfo) {
                        if (!mNsdServiceInfoMap.containsKey(serviceName)) {
                            return;
                        }

                        final LssServiceInfo lssServiceInfo = buildServiceInfo(serviceInfo);
                        Log.i(TAG, String.format("mNsdDiscoveryListener.onServiceFound: service resolved"
                            + ", serviceInfo = %s, lssServiceInfo = %s", serviceInfo, lssServiceInfo));

                        mNsdServiceInfoMap.put(serviceName, serviceInfo);

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

    private void startDiscovery() {
        Log.i(TAG, "startDiscovery: start discovery");
        mNsdManager.discoverServices(Constants.NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, mNsdDiscoveryListener);
    }

    private void stopDiscovery() {
        Log.i(TAG, "stopDiscovery: stop discovery");
        mNsdManager.stopServiceDiscovery(mNsdDiscoveryListener);
        mNsdServiceInfoMap.clear();
        mServiceInfoMap.clear();
        updateServiceInfoList();
    }

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

    private static class InstanceHolder {
        private static final LssServiceDiscoveryManager INSTANCE = new LssServiceDiscoveryManager();
    }
}
