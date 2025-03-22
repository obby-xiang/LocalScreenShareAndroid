package com.obby.android.localscreenshare.server;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.protobuf.Empty;
import com.obby.android.localscreenshare.discovery.LssServiceInfo;
import com.obby.android.localscreenshare.grpc.screenstream.ScreenFrame;
import com.obby.android.localscreenshare.grpc.screenstream.ScreenStreamServiceGrpc;
import com.obby.android.localscreenshare.support.Constants;
import com.obby.android.localscreenshare.support.Preferences;
import com.obby.android.localscreenshare.utils.NsdUtils;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.Setter;
import lombok.experimental.Accessors;

public final class LssServer {
    @Nullable
    private LssServiceInfo mServiceInfo;

    @Nullable
    private NsdServiceInfo mNsdServiceInfo;

    private final String mTag = "LssServer@" + hashCode();

    @NonNull
    private final Context mContext;

    @NonNull
    private final NsdManager mNsdManager;

    @NonNull
    private final ConnectivityManager mConnectivityManager;

    @NonNull
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @NonNull
    private final ExecutorService mGrpcServerExecutor = new ThreadPoolExecutor(1, 4, 10L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(100), new BasicThreadFactory.Builder()
        .namingPattern("lss-server-grpc-%d")
        .wrappedFactory(runnable -> new Thread(runnable) {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                super.run();
            }
        })
        .build(), new ThreadPoolExecutor.DiscardOldestPolicy());

    @NonNull
    private final ScreenStreamService mScreenStreamService = new ScreenStreamService(mGrpcServerExecutor);

    @NonNull
    private final Server mGrpcServer = Grpc.newServerBuilderForPort(Preferences.get().getLssServerPort(),
            InsecureServerCredentials.create())
        .executor(mGrpcServerExecutor)
        .addService(mScreenStreamService)
        .build();

    @NonNull
    private final Runnable mResolveNsdServiceRunnable = new Runnable() {
        @Override
        public void run() {
            if (mNsdServiceInfo != null) {
                NsdUtils.resolveService(mNsdManager, mNsdServiceInfo, mNsdResolveListener);
            }
        }
    };

    @NonNull
    private final NsdManager.RegistrationListener mNsdRegistrationListener = new NsdManager.RegistrationListener() {
        @Override
        public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            Log.e(mTag, String.format("mNsdRegistrationListener.onRegistrationFailed: register service failed"
                + ", serviceInfo = %s, errorCode = %d", serviceInfo, errorCode));
            mMainHandler.post(() -> {
                if (mNsdServiceInfo == null) {
                    return;
                }
                Log.i(mTag, "mNsdRegistrationListener.onRegistrationFailed: re-register service");
                mNsdManager.unregisterService(mNsdRegistrationListener);
                mNsdManager.registerService(mNsdServiceInfo, NsdManager.PROTOCOL_DNS_SD, mNsdRegistrationListener);
            });
        }

        @SuppressWarnings("SpellCheckingInspection")
        @Override
        public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            Log.e(mTag, String.format("mNsdRegistrationListener.onUnregistrationFailed: unregister service failed"
                + ", serviceInfo = %s, errorCode = %d", serviceInfo, errorCode));
        }

        @Override
        public void onServiceRegistered(NsdServiceInfo serviceInfo) {
            Log.i(mTag, String.format("mNsdRegistrationListener.onServiceRegistered: service registered"
                + ", serviceInfo = %s", serviceInfo));
            postResolveNsdServiceRunnable();
        }

        @Override
        public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
            Log.i(mTag, String.format("mNsdRegistrationListener.onServiceUnregistered: service unregistered"
                + ", serviceInfo = %s", serviceInfo));
        }
    };

    @NonNull
    private final NsdManager.ResolveListener mNsdResolveListener = new NsdManager.ResolveListener() {
        @Override
        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
            Log.e(mTag, String.format("mNsdResolveListener.onResolveFailed: service resolve failed, serviceInfo = %s"
                + ", errorCode = %d", serviceInfo, errorCode));
            postResolveNsdServiceRunnable();
        }

        @Override
        public void onServiceResolved(NsdServiceInfo serviceInfo) {
            final String hostAddress = Optional.ofNullable(serviceInfo.getHost())
                .map(InetAddress::getHostAddress)
                .orElse(null);
            Log.i(mTag, String.format("mNsdResolveListener.onServiceResolved: service resolved, serviceInfo = %s"
                + ", hostAddress = %s", serviceInfo, hostAddress));

            if (TextUtils.isEmpty(hostAddress)) {
                postResolveNsdServiceRunnable();
            } else {
                mMainHandler.post(() -> {
                    if (mServiceInfo != null) {
                        updateServerInfo(mServiceInfo.toBuilder().hostAddress(hostAddress).build());
                    }
                });
            }
        }
    };

    @NonNull
    private final ConnectivityManager.NetworkCallback mNetworkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            Log.i(mTag, String.format("mNetworkCallback.onAvailable: network available, network = %s", network));
            postResolveNsdServiceRunnable();
        }

        @Override
        public void onLost(@NonNull Network network) {
            Log.i(mTag, String.format("mNetworkCallback.onLost: network lost, network = %s", network));
            postResolveNsdServiceRunnable();
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
            Log.i(mTag, String.format("mNetworkCallback.onCapabilitiesChanged: network capabilities changed"
                + ", network = %s, networkCapabilities = %s", network, networkCapabilities));
            postResolveNsdServiceRunnable();
        }
    };

    @NonNull
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Constants.ACTION_WIFI_AP_STATE_CHANGED.equals(intent.getAction())) {
                postResolveNsdServiceRunnable();
            }
        }
    };

    public LssServer(@NonNull final Context context) {
        mContext = context;
        mNsdManager = mContext.getSystemService(NsdManager.class);
        mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
    }

    public void start() throws IOException {
        mGrpcServer.start();

        mServiceInfo = LssServiceInfo.builder()
            .id(Preferences.get().getLssServiceId())
            .name(Preferences.get().getLssServiceName())
            .hostAddress(getHostAddress(mGrpcServer))
            .port(mGrpcServer.getPort())
            .build();
        mNsdServiceInfo = buildNsdServiceInfo(mServiceInfo);
        mNsdManager.registerService(mNsdServiceInfo, NsdManager.PROTOCOL_DNS_SD, mNsdRegistrationListener);
        mConnectivityManager.registerDefaultNetworkCallback(mNetworkCallback);

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION_WIFI_AP_STATE_CHANGED);
        ContextCompat.registerReceiver(mContext, mBroadcastReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    public void stop() {
        mServiceInfo = null;
        mNsdServiceInfo = null;
        mMainHandler.removeCallbacksAndMessages(null);
        mContext.unregisterReceiver(mBroadcastReceiver);
        mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
        mNsdManager.unregisterService(mNsdRegistrationListener);
        mGrpcServer.shutdownNow();
        mScreenStreamService.stop();
        mGrpcServerExecutor.shutdownNow();
    }

    public void dispatchScreenFrame(@NonNull final ScreenFrame frame) {
        mScreenStreamService.dispatchScreenFrame(frame);
    }

    private void updateServerInfo(@NonNull final LssServiceInfo serviceInfo) {
        if (Objects.equals(mServiceInfo, serviceInfo)) {
            Log.w(mTag, "updateServerInfo: service info not changed");
            return;
        }

        mServiceInfo = serviceInfo;
        Log.i(mTag, String.format("updateServerInfo: service info changed, serviceInfo = %s", mServiceInfo));
    }

    private void postResolveNsdServiceRunnable() {
        mMainHandler.removeCallbacks(mResolveNsdServiceRunnable);
        mMainHandler.post(mResolveNsdServiceRunnable);
    }

    @NonNull
    private NsdServiceInfo buildNsdServiceInfo(@NonNull final LssServiceInfo serviceInfo) {
        final NsdServiceInfo nsdServiceInfo = new NsdServiceInfo();
        nsdServiceInfo.setServiceName(serviceInfo.getId());
        nsdServiceInfo.setServiceType(Constants.NSD_SERVICE_TYPE);
        nsdServiceInfo.setPort(mGrpcServer.getPort());
        nsdServiceInfo.setAttribute(Constants.NSD_SERVICE_ATTR_ID, serviceInfo.getId());
        nsdServiceInfo.setAttribute(Constants.NSD_SERVICE_ATTR_NAME, serviceInfo.getName());
        return nsdServiceInfo;
    }

    @NonNull
    private String getHostAddress(@NonNull final Server server) {
        return ObjectUtils.defaultIfNull(server.getListenSockets(), Collections.emptyList())
            .stream()
            .filter(socketAddress -> socketAddress instanceof InetSocketAddress)
            .findFirst()
            .map(socketAddress -> ((InetSocketAddress) socketAddress).getAddress())
            .map(InetAddress::getHostAddress)
            .orElse(StringUtils.EMPTY);
    }

    private static class ScreenStreamService extends ScreenStreamServiceGrpc.ScreenStreamServiceImplBase {
        private volatile boolean mIsStopped;

        private volatile ScreenFrame mScreenFrame;

        @NonNull
        private final Executor mExecutor;

        @NonNull
        private final Object mLock = new Object();

        @NonNull
        private final List<ScreenStreamResponseObserver> mScreenStreamResponseObservers = new CopyOnWriteArrayList<>();

        private ScreenStreamService(@NonNull final Executor executor) {
            mExecutor = executor;
        }

        @Override
        public void getScreenStream(Empty request, StreamObserver<ScreenFrame> responseObserver) {
            if (mIsStopped) {
                return;
            }

            synchronized (mLock) {
                if (mIsStopped) {
                    return;
                }

                final ScreenStreamResponseObserver observer = new ScreenStreamResponseObserver(
                    (ServerCallStreamObserver<ScreenFrame>) responseObserver, mExecutor);
                observer.setOnReleaseListener(() -> {
                    synchronized (mLock) {
                        mScreenStreamResponseObservers.remove(observer);
                    }
                });
                mScreenStreamResponseObservers.add(observer);

                if (mScreenFrame != null) {
                    observer.send(mScreenFrame);
                }
            }
        }

        public void dispatchScreenFrame(@NonNull final ScreenFrame frame) {
            if (mIsStopped) {
                return;
            }

            synchronized (mLock) {
                if (mIsStopped) {
                    return;
                }

                mScreenFrame = frame;
                mScreenStreamResponseObservers.forEach(observer -> observer.send(mScreenFrame));
            }
        }

        public void stop() {
            synchronized (mLock) {
                mIsStopped = true;
                mScreenFrame = null;
                mScreenStreamResponseObservers.forEach(ScreenStreamResponseObserver::release);
                mScreenStreamResponseObservers.clear();
            }
        }
    }

    @Accessors(prefix = "m")
    private static class ScreenStreamResponseObserver {
        private volatile boolean mIsReleased;

        private volatile ScreenFrame mScreenFrame;

        @Setter
        @Nullable
        private OnReleaseListener mOnReleaseListener;

        @NonNull
        private final ServerCallStreamObserver<ScreenFrame> mObserver;

        @NonNull
        private final Executor mExecutor;

        @NonNull
        private final Object mLock = new Object();

        @NonNull
        private final AtomicLong mLastScreenFrameTimestamp = new AtomicLong(-1L);

        @NonNull
        private final Runnable mOnReadyHandler = new Runnable() {
            @Override
            public void run() {
                if (mIsReleased || mScreenFrame == null) {
                    return;
                }

                synchronized (mLock) {
                    if (mIsReleased || mScreenFrame == null) {
                        return;
                    }

                    if (mLastScreenFrameTimestamp.get() < mScreenFrame.getTimestamp()) {
                        mObserver.onNext(mScreenFrame);
                        mLastScreenFrameTimestamp.set(mScreenFrame.getTimestamp());
                    }

                    mScreenFrame = null;
                }
            }
        };

        private ScreenStreamResponseObserver(@NonNull final ServerCallStreamObserver<ScreenFrame> observer,
            @NonNull final Executor executor) {
            mObserver = observer;
            mExecutor = executor;
            mObserver.setOnReadyHandler(mOnReadyHandler);
            mObserver.setOnCloseHandler(this::release);
            mObserver.setOnCancelHandler(this::release);
        }

        public void send(@NonNull final ScreenFrame screenFrame) {
            if (mIsReleased) {
                return;
            }

            synchronized (mLock) {
                if (mIsReleased) {
                    return;
                }

                mScreenFrame = screenFrame;
                if (mObserver.isReady()) {
                    mExecutor.execute(mOnReadyHandler);
                }
            }
        }

        public void release() {
            synchronized (mLock) {
                mIsReleased = true;
                mScreenFrame = null;
                if (mOnReleaseListener != null) {
                    mOnReleaseListener.onRelease();
                }
            }
        }

        @FunctionalInterface
        private interface OnReleaseListener {
            void onRelease();
        }
    }
}
