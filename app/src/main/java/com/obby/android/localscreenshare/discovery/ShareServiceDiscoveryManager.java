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
import com.obby.android.localscreenshare.utils.ThreadUtils;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import lombok.Getter;
import lombok.experimental.Accessors;

@Accessors(prefix = "m")
public final class ShareServiceDiscoveryManager {
    private static final String TAG = "ShareServiceDiscoveryManager";

    private boolean mIsDiscovering;

    @Getter
    @NonNull
    private List<ShareServiceInfo> mShareServiceInfoList = Collections.emptyList();

    @NonNull
    private final NsdManager mNsdManager;

    @NonNull
    private final Map<String, NsdServiceInfo> mNsdServiceInfoMap = new ConcurrentHashMap<>();

    @NonNull
    private final Map<String, ShareServiceInfo> mShareServiceInfoMap = new ConcurrentHashMap<>();

    @NonNull
    private final List<ShareServiceDiscoveryListener> mShareDiscoveryListeners = new CopyOnWriteArrayList<>();

    @NonNull
    private final Executor mResolveNsdServiceExecutor = Executors.newSingleThreadExecutor();

    @NonNull
    private final NsdManager.DiscoveryListener mNsdDiscoveryListener =
        new NsdDiscoveryListenerWrapper(new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, String.format("mNsdDiscoveryListener: start discovery failed, serviceType = %s"
                    + ", errorCode = %d", serviceType, errorCode));
                if (mIsDiscovering) {
                    Log.i(TAG, "mNsdDiscoveryListener: restart discovery");
                    stopDiscovery();
                    startDiscovery();
                }
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, String.format("mNsdDiscoveryListener: stop discovery failed, serviceType = %s"
                    + ", errorCode = %d", serviceType, errorCode));
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.i(TAG, String.format("mNsdDiscoveryListener: discovery started, serviceType = %s", serviceType));
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i(TAG, String.format("mNsdDiscoveryListener: discovery stopped, serviceType = %s", serviceType));
                if (mIsDiscovering) {
                    Log.i(TAG, "mNsdDiscoveryListener: restart discovery");
                    stopDiscovery();
                    startDiscovery();
                }
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                Log.i(TAG, String.format("mNsdDiscoveryListener: service found, serviceInfo = %s", serviceInfo));

                if (TextUtils.isEmpty(serviceInfo.getServiceName())) {
                    return;
                }

                final String serviceName = serviceInfo.getServiceName();
                mNsdServiceInfoMap.put(serviceName, serviceInfo);

                resolveNsdService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                        if (!mNsdServiceInfoMap.containsKey(serviceName)) {
                            return;
                        }
                        Log.e(TAG, String.format("mNsdDiscoveryListener: service resolve failed, serviceInfo = %s"
                            + ", errorCode = %d", serviceInfo, errorCode));
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo serviceInfo) {
                        if (!mNsdServiceInfoMap.containsKey(serviceName)) {
                            return;
                        }

                        final ShareServiceInfo shareServiceInfo = buildShareServiceInfo(serviceInfo);
                        Log.i(TAG, String.format("mNsdDiscoveryListener: service resolved, serviceInfo = %s"
                            + ", shareServiceInfo = %s", serviceInfo, shareServiceInfo));

                        mNsdServiceInfoMap.put(serviceName, serviceInfo);

                        if (shareServiceInfo == null
                            || Objects.equals(shareServiceInfo.getId(), Preferences.get().getShareServiceId())) {
                            mShareServiceInfoMap.remove(serviceName);
                        } else {
                            mShareServiceInfoMap.entrySet()
                                .stream()
                                .filter(element ->
                                    Objects.equals(element.getValue().getId(), Preferences.get().getShareServiceId()))
                                .map(Map.Entry::getKey)
                                .forEach(mShareServiceInfoMap::remove);
                            mShareServiceInfoMap.put(serviceName, shareServiceInfo);
                        }

                        updateShareServiceInfoList();
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                Log.i(TAG, String.format("mNsdDiscoveryListener: service lost, serviceInfo = %s", serviceInfo));
                if (!TextUtils.isEmpty(serviceInfo.getServiceName())) {
                    mNsdServiceInfoMap.remove(serviceInfo.getServiceName());
                    mShareServiceInfoMap.remove(serviceInfo.getServiceName());
                    updateShareServiceInfoList();
                }
            }
        }, ThreadUtils.getMainThreadExecutor());

    private ShareServiceDiscoveryManager() {
        mNsdManager = App.get().getSystemService(NsdManager.class);
    }

    @NonNull
    public static ShareServiceDiscoveryManager get() {
        return InstanceHolder.INSTANCE;
    }

    public void discoverServices(@NonNull final ShareServiceDiscoveryListener listener) {
        if (mShareDiscoveryListeners.contains(listener)) {
            return;
        }

        mShareDiscoveryListeners.add(listener);
        if (!mIsDiscovering) {
            mIsDiscovering = true;
            startDiscovery();
        }
    }

    public void stopServiceDiscovery(@NonNull final ShareServiceDiscoveryListener listener) {
        mShareDiscoveryListeners.remove(listener);

        if (mShareDiscoveryListeners.isEmpty() && mIsDiscovering) {
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
        mShareServiceInfoMap.clear();
        updateShareServiceInfoList();
    }

    private void updateShareServiceInfoList() {
        Log.i(TAG, "updateShareServiceInfoList: update share service info list");

        final List<ShareServiceInfo> serviceInfoList = new ArrayList<>(mShareServiceInfoMap.values());
        Collections.sort(serviceInfoList);

        if (Objects.equals(mShareServiceInfoList, serviceInfoList)) {
            Log.w(TAG, "updateShareServiceInfoList: share service info list not changed");
            return;
        }

        mShareServiceInfoList = Collections.unmodifiableList(serviceInfoList);
        mShareDiscoveryListeners.forEach(element -> element.onServicesChanged(mShareServiceInfoList));
    }

    @Nullable
    private ShareServiceInfo buildShareServiceInfo(@NonNull final NsdServiceInfo serviceInfo) {
        final Map<String, byte[]> attributes = serviceInfo.getAttributes();
        final String id = Optional.ofNullable(attributes.get(Constants.NSD_SERVICE_ATTR_ID))
            .map(value -> new String(value, StandardCharsets.UTF_8))
            .orElse(null);
        final String name = Optional.ofNullable(attributes.get(Constants.NSD_SERVICE_ATTR_NAME))
            .map(value -> new String(value, StandardCharsets.UTF_8))
            .orElse(null);
        final String hostAddress = Optional.ofNullable(serviceInfo.getHost())
            .map(InetAddress::getHostAddress)
            .orElse(null);
        if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name) || TextUtils.isEmpty(hostAddress)) {
            return null;
        }

        return ShareServiceInfo.builder()
            .id(id)
            .name(name)
            .hostAddress(hostAddress)
            .port(serviceInfo.getPort())
            .build();
    }

    private void resolveNsdService(@NonNull final NsdServiceInfo serviceInfo,
        @NonNull final NsdManager.ResolveListener listener) {
        mResolveNsdServiceExecutor.execute(() -> {
            final CountDownLatch latch = new CountDownLatch(1);
            final Executor executor = ThreadUtils.getMainThreadExecutor();

            mNsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    latch.countDown();
                    executor.execute(() -> listener.onResolveFailed(serviceInfo, errorCode));
                }

                @Override
                public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    latch.countDown();
                    executor.execute(() -> listener.onServiceResolved(serviceInfo));
                }
            });

            try {
                latch.await();
            } catch (InterruptedException e) {
                // ignored
            }
        });
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
        private static final ShareServiceDiscoveryManager INSTANCE = new ShareServiceDiscoveryManager();
    }
}
