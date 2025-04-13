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
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.protobuf.Empty;
import com.obby.android.localscreenshare.grpc.screenstream.ScreenFrame;
import com.obby.android.localscreenshare.grpc.screenstream.ScreenStreamServiceGrpc;
import com.obby.android.localscreenshare.support.Constants;
import com.obby.android.localscreenshare.support.Preferences;
import com.obby.android.localscreenshare.utils.NetUtils;
import com.obby.android.localscreenshare.utils.NsdUtils;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
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
import java.util.stream.Collectors;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerStreamTracer;
import io.grpc.ServerTransportFilter;
import io.grpc.okhttp.OkHttpServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Accessors(prefix = "m")
public final class LssServer {
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    private static final long UPDATE_SERVER_STATS_INTERVAL_MS = 3000L;

    @Nullable
    private LssServerInfo mServerInfo;

    @Nullable
    private NsdServiceInfo mNsdServiceInfo;

    @Nullable
    private ServerProfile mServerProfile;

    @Nullable
    private LssServerStats mServerStats;

    @Setter
    @Nullable
    private LssServerInfoListener mServerInfoListener;

    @Setter
    @Nullable
    private LssServerStatsListener mServerStatsListener;

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
    private final ExecutorService mGrpcServerExecutor = new ThreadPoolExecutor(CPU_COUNT, CPU_COUNT * 2, 30L,
        TimeUnit.SECONDS, new LinkedBlockingQueue<>(200), new BasicThreadFactory.Builder()
        .namingPattern("lss-grpc-server-%d")
        .wrappedFactory(runnable -> new Thread(runnable) {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                super.run();
            }
        })
        .build(), new ThreadPoolExecutor.CallerRunsPolicy());

    @NonNull
    private final ScreenStreamService mScreenStreamService = new ScreenStreamService(mGrpcServerExecutor);

    @NonNull
    private final ServerTransportFilter mServerTransportFilter = new ServerTransportFilter() {
        @SuppressWarnings("DataFlowIssue")
        @Override
        public Attributes transportReady(Attributes transportAttrs) {
            Log.i(mTag, String.format("mServerTransportFilter.transportReady: transport ready, transportAttrs = %s",
                transportAttrs));

            final InetSocketAddress remoteAddress =
                (InetSocketAddress) transportAttrs.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
            Optional.ofNullable(mServerProfile).ifPresent(serverProfile -> serverProfile.addTransport(remoteAddress));

            return super.transportReady(transportAttrs);
        }

        @SuppressWarnings("DataFlowIssue")
        @Override
        public void transportTerminated(Attributes transportAttrs) {
            super.transportTerminated(transportAttrs);
            Log.i(mTag, String.format("mServerTransportFilter.transportTerminated: transport terminated"
                + ", transportAttrs = %s", transportAttrs));

            final InetSocketAddress remoteAddress =
                (InetSocketAddress) transportAttrs.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
            Optional.ofNullable(mServerProfile)
                .ifPresent(serverProfile -> serverProfile.removeTransport(remoteAddress));
        }
    };

    @NonNull
    private final ServerStreamTracer.Factory mServerStreamTracerFactory = new ServerStreamTracer.Factory() {
        @Override
        public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
            return new ServerStreamTracer() {
                @Nullable
                private ServerProfile.TransportProfile mTransportProfile;

                @SuppressWarnings("DataFlowIssue")
                @Override
                public void serverCallStarted(ServerCallInfo<?, ?> callInfo) {
                    super.serverCallStarted(callInfo);
                    final InetSocketAddress remoteAddress =
                        (InetSocketAddress) callInfo.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
                    mTransportProfile = Optional.ofNullable(mServerProfile)
                        .map(serverProfile -> serverProfile.getTransport(remoteAddress))
                        .orElse(null);
                }

                @Override
                public void outboundWireSize(long bytes) {
                    super.outboundWireSize(bytes);
                    Optional.ofNullable(mTransportProfile)
                        .ifPresent(transportProfile -> transportProfile.addOutboundDataSize(bytes));
                }
            };
        }
    };

    @NonNull
    private final Server mGrpcServer = OkHttpServerBuilder.forPort(Preferences.get().getLssServerPort(),
            InsecureServerCredentials.create())
        .executor(mGrpcServerExecutor)
        .flowControlWindow(Constants.GRPC_FLOW_CONTROL_WINDOW)
        .maxInboundMessageSize(Constants.GRPC_MAX_INBOUND_MESSAGE_SIZE)
        .addTransportFilter(mServerTransportFilter)
        .addStreamTracerFactory(mServerStreamTracerFactory)
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
    private final Runnable mUpdateServerStatsRunnable = new Runnable() {
        @Override
        public void run() {
            mMainHandler.removeCallbacks(mUpdateServerStatsRunnable);
            if (mServerProfile == null) {
                return;
            }

            final LssServerStats serverStats = mServerProfile.collect();
            if (Objects.equals(mServerStats, serverStats)) {
                Log.w(mTag, "mUpdateServerStatsRunnable: server stats not changed");
            } else {
                mServerStats = serverStats;
                Log.i(mTag, String.format("mUpdateServerStatsRunnable: server stats changed, serverStats = %s",
                    mServerStats));

                if (mServerStatsListener != null) {
                    mServerStatsListener.onServerStatsChanged(mServerStats);
                }
            }

            mMainHandler.postDelayed(mUpdateServerStatsRunnable, UPDATE_SERVER_STATS_INTERVAL_MS);
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
                .map(NetUtils::getHostAddress)
                .orElse(null);
            Log.i(mTag, String.format("mNsdResolveListener.onServiceResolved: service resolved, serviceInfo = %s"
                + ", hostAddress = %s", serviceInfo, hostAddress));

            if (TextUtils.isEmpty(hostAddress)) {
                postResolveNsdServiceRunnable();
            } else {
                mMainHandler.post(() -> {
                    if (mServerInfo != null) {
                        updateServerInfo(mServerInfo.toBuilder().hostAddress(hostAddress).build());
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

        final String hostAddress =
            NetUtils.getHostAddress(((InetSocketAddress) mGrpcServer.getListenSockets().get(0)).getAddress());
        mServerInfo = LssServerInfo.builder()
            .id(Preferences.get().getLssServiceId())
            .name(Preferences.get().getLssServiceName())
            .hostAddress(hostAddress)
            .port(mGrpcServer.getPort())
            .build();
        mNsdServiceInfo = buildNsdServiceInfo(mServerInfo);
        mNsdManager.registerService(mNsdServiceInfo, NsdManager.PROTOCOL_DNS_SD, mNsdRegistrationListener);
        mConnectivityManager.registerDefaultNetworkCallback(mNetworkCallback);

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION_WIFI_AP_STATE_CHANGED);
        ContextCompat.registerReceiver(mContext, mBroadcastReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);

        if (mServerInfoListener != null) {
            mServerInfoListener.onServerInfoChanged(mServerInfo);
        }

        mServerProfile = new ServerProfile();
        mMainHandler.postDelayed(mUpdateServerStatsRunnable, UPDATE_SERVER_STATS_INTERVAL_MS);
    }

    public void stop() {
        mServerInfo = null;
        mNsdServiceInfo = null;
        mServerProfile = null;
        mServerStats = null;
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

    private void updateServerInfo(@NonNull final LssServerInfo serverInfo) {
        if (Objects.equals(mServerInfo, serverInfo)) {
            Log.w(mTag, "updateServerInfo: server info not changed");
            return;
        }

        mServerInfo = serverInfo;
        Log.i(mTag, String.format("updateServerInfo: server info changed, serverInfo = %s", mServerInfo));

        if (mServerInfoListener != null) {
            mServerInfoListener.onServerInfoChanged(mServerInfo);
        }
    }

    private void postResolveNsdServiceRunnable() {
        mMainHandler.removeCallbacks(mResolveNsdServiceRunnable);
        mMainHandler.post(mResolveNsdServiceRunnable);
    }

    @NonNull
    private NsdServiceInfo buildNsdServiceInfo(@NonNull final LssServerInfo serverInfo) {
        final NsdServiceInfo nsdServiceInfo = new NsdServiceInfo();
        nsdServiceInfo.setServiceName(serverInfo.getId());
        nsdServiceInfo.setServiceType(Constants.NSD_SERVICE_TYPE);
        nsdServiceInfo.setPort(mGrpcServer.getPort());
        nsdServiceInfo.setAttribute(Constants.NSD_SERVICE_ATTR_ID, serverInfo.getId());
        nsdServiceInfo.setAttribute(Constants.NSD_SERVICE_ATTR_NAME, serverInfo.getName());
        return nsdServiceInfo;
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

    @Accessors(prefix = "m")
    private static class ServerProfile {
        @NonNull
        private final Object mLock = new Object();

        @NonNull
        private final AtomicLong mTimestamp = new AtomicLong(SystemClock.elapsedRealtimeNanos());

        @NonNull
        private final List<TransportProfile> mTransports = new CopyOnWriteArrayList<>();

        public void addTransport(@NonNull final InetSocketAddress remoteAddress) {
            synchronized (mLock) {
                if (getTransport(remoteAddress) == null) {
                    mTransports.add(new TransportProfile(remoteAddress));
                }
            }
        }

        public void removeTransport(@NonNull final InetSocketAddress remoteAddress) {
            synchronized (mLock) {
                final TransportProfile transportProfile = getTransport(remoteAddress);
                if (transportProfile != null) {
                    mTransports.remove(transportProfile);
                }
            }
        }

        @Nullable
        public TransportProfile getTransport(@NonNull final InetSocketAddress remoteAddress) {
            return mTransports.stream()
                .filter(transportProfile -> Objects.equals(transportProfile.getRemoteAddress(), remoteAddress))
                .findFirst()
                .orElse(null);
        }

        @NonNull
        public LssServerStats collect() {
            synchronized (mLock) {
                final long startTimestamp = mTimestamp.getAndSet(SystemClock.elapsedRealtimeNanos());
                final long endTimestamp = mTimestamp.get();
                final List<LssServerStats.TransportStats> transports = mTransports.stream()
                    .map(transportProfile -> {
                        final long outboundDataSize = transportProfile.getAndResetOutboundDataSize();
                        final long outboundDataRate = Math.round((double) outboundDataSize
                            / (endTimestamp - startTimestamp) * Duration.ofSeconds(1L).toNanos());
                        return LssServerStats.TransportStats.builder()
                            .remoteAddress(transportProfile.getRemoteAddress().toString())
                            .outboundDataSize(outboundDataSize)
                            .outboundDataRate(outboundDataRate)
                            .build();
                    })
                    .collect(Collectors.toUnmodifiableList());
                final long outboundDataSize = transports.stream()
                    .map(LssServerStats.TransportStats::getOutboundDataSize)
                    .reduce(0L, Long::sum);
                final long outboundDataRate = Math.round((double) outboundDataSize / (endTimestamp - startTimestamp)
                    * Duration.ofSeconds(1L).toNanos());

                return LssServerStats.builder()
                    .outboundDataSize(outboundDataSize)
                    .outboundDataRate(outboundDataRate)
                    .transports(transports)
                    .build();
            }
        }

        @Accessors(prefix = "m")
        private static class TransportProfile {
            @Getter(AccessLevel.PRIVATE)
            @NonNull
            private final InetSocketAddress mRemoteAddress;

            @NonNull
            private final AtomicLong mOutboundDataSize = new AtomicLong();

            private TransportProfile(@NonNull final InetSocketAddress remoteAddress) {
                mRemoteAddress = remoteAddress;
            }

            public void addOutboundDataSize(final long size) {
                mOutboundDataSize.addAndGet(size);
            }

            public long getAndResetOutboundDataSize() {
                return mOutboundDataSize.getAndSet(0L);
            }
        }
    }
}
