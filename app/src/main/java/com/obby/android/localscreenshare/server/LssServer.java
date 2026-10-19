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
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.stream.StreamHub;
import com.obby.android.localscreenshare.core.stream.StreamRuntime;
import com.obby.android.localscreenshare.grpc.screenstream.ClientMessage;
import com.obby.android.localscreenshare.grpc.screenstream.ScreenFrame;
import com.obby.android.localscreenshare.grpc.screenstream.ScreenStreamServiceGrpc;
import com.obby.android.localscreenshare.grpc.screenstream.ServerMessage;
import com.obby.android.localscreenshare.support.Constants;
import com.obby.android.localscreenshare.transport.GrpcMediaTransport;
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
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerStreamTracer;
import io.grpc.ServerTransportFilter;
import io.grpc.Status;
import io.grpc.okhttp.OkHttpServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * Legacy gRPC server that advertises itself over NSD and streams JPEG frames to connected clients.
 *
 * <p><b>Lifecycle.</b> The constructor builds the gRPC server object and its executor but does not
 * bind a port. {@link #start()} binds the configured port, registers the NSD service, the
 * default-network callback and the Wi-Fi AP state receiver, publishes an initial
 * {@link LssServerInfo} with a placeholder host address, and starts the periodic stats sampling.
 * {@link #stop()} releases all of them in reverse order and shuts the gRPC executor down; late
 * NSD, network and gRPC callbacks then observe nulled state and become no-ops.</p>
 *
 * <p><b>Host address discovery.</b> The host address is not known when the gRPC port is bound. The
 * server resolves its own NSD registration and re-resolves it whenever the default network
 * changes, a network capability changes or the Wi-Fi AP state changes; resolve failures are
 * retried. {@link LssServerInfoListener} is notified whenever the resulting address changes.</p>
 *
 * <p><b>Threading.</b> {@link #start()}, {@link #stop()}, the info and stats listeners and all
 * state changes run on the main thread; NSD and connectivity callbacks are posted to the main
 * thread first. gRPC service methods and client send handlers run on the private
 * {@code lss-grpc-server-*} executor, while transport filters and stream tracers run on gRPC
 * transport threads. {@link #postScreenFrame} may be called from any thread, typically the capture
 * thread.</p>
 *
 * <p><b>Fan-out and conflation.</b> Each connected client gets its own stream observer. Only the
 * newest frame is kept: a client that is not ready for more data receives the latest frame once it
 * becomes ready, and frames with a non-increasing timestamp are never sent twice.</p>
 *
 * <p><b>Stats.</b> Outbound wire bytes are accounted per remote transport by a stream tracer and
 * sampled every 3 seconds on the main thread; the stats listener is only invoked when the sampled
 * values change.</p>
 */
@Accessors(prefix = "m")
public final class LssServer {
    private static final long UPDATE_SERVER_STATS_INTERVAL_MS = 3000L;

    // Published identity; null while stopped.
    @Nullable
    private LssServerInfo mServerInfo;

    // Registration used for resolve and re-register attempts; null while stopped.
    @Nullable
    private NsdServiceInfo mNsdServiceInfo;

    // Per-transport accounting; null while stopped.
    @Nullable
    private ServerProfile mServerProfile;

    // Last sampled value; used to suppress duplicate listener calls.
    @Nullable
    private LssServerStats mServerStats;

    @Setter
    @Nullable
    private LssServerInfoListener mServerInfoListener;

    @Setter
    @Nullable
    private LssServerStatsListener mServerStatsListener;

    // Media stream wiring: the hub owns per-client send sessions and the runtime owns the encoder.
    // Both are assembled and started by LssService before the server accepts streams.
    @Nullable
    private StreamHub mStreamHub;

    @Nullable
    private StreamRuntime mStreamRuntime;

    // Session-level secure flag applied to every descriptor; set by LssService.
    private volatile boolean mSecure;

    // Set by stop(); late stream calls become no-ops.
    private volatile boolean mIsStopped;

    private final String mTag = "LssServer@" + hashCode();

    @NonNull
    private final Context mContext;

    @NonNull
    private final NsdManager mNsdManager;

    @NonNull
    private final ConnectivityManager mConnectivityManager;

    @NonNull
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // Serves gRPC callbacks only. Media fan-out now runs on per-client drain threads driven by the
    // stream hub, so rejected work is aborted instead of running on the caller's thread.
    @NonNull
    private final ExecutorService mGrpcServerExecutor = new ThreadPoolExecutor(Constants.CPU_COUNT,
        Constants.CPU_COUNT * 2, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(10), new BasicThreadFactory.Builder()
        .namingPattern("lss-grpc-server-%d")
        .wrappedFactory(runnable -> new Thread(runnable) {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                super.run();
            }
        })
        .build(), new ThreadPoolExecutor.AbortPolicy());

    @NonNull
    private final ScreenStreamService mScreenStreamService = new ScreenStreamService(mGrpcServerExecutor);

    @NonNull
    private final StreamService mStreamService = new StreamService();

    // Records each open transport as a per-remote-address entry; called on gRPC transport threads.
    @NonNull
    private final ServerTransportFilter mServerTransportFilter = new ServerTransportFilter() {
        @SuppressWarnings("DataFlowIssue")
        @Override
        public Attributes transportReady(Attributes transportAttrs) {
            Log.i(mTag, String.format("mServerTransportFilter.transportReady: transport ready, transportAttrs = %s",
                transportAttrs));

            // Registers one profile per remote address when its first transport becomes ready.
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

            if (transportAttrs != null) {
                // Drops the profile for the terminated transport's remote address.
                final InetSocketAddress remoteAddress =
                    (InetSocketAddress) transportAttrs.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
                Optional.ofNullable(mServerProfile)
                    .ifPresent(serverProfile -> serverProfile.removeTransport(remoteAddress));
            }
        }
    };

    // Captures the transport profile when the call starts and adds outbound wire bytes to it; runs
    // on gRPC transport threads.
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

    // Built by the constructor; bound by start() and shut down by stop().
    @NonNull
    private final Server mGrpcServer =
        OkHttpServerBuilder.forPort(Preferences.get().getServerPort(), InsecureServerCredentials.create())
            .flowControlWindow(Constants.GRPC_FLOW_CONTROL_WINDOW)
            .maxInboundMessageSize(Constants.GRPC_MAX_INBOUND_MESSAGE_SIZE)
            .executor(mGrpcServerExecutor)
            .addTransportFilter(mServerTransportFilter)
            .addStreamTracerFactory(mServerStreamTracerFactory)
            .addService(mScreenStreamService)
            .addService(mStreamService)
            .build();

    // Re-resolves the own registration. Every caller goes through postResolveNsdServiceRunnable(),
    // which coalesces pending requests into one main-thread run.
    @NonNull
    private final Runnable mResolveNsdServiceRunnable = new Runnable() {
        @Override
        public void run() {
            if (mNsdServiceInfo != null) {
                NsdUtils.resolveService(mNsdManager, mNsdServiceInfo, mNsdResolveListener);
            }
        }
    };

    // Reschedules itself every 3 seconds and reports only sampled changes; runs on the main thread.
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

    // Re-registers on every failure while a registration is still wanted; callbacks arrive on an
    // NSD thread, so the retry is posted to the main thread.
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

    // Applies a resolved host address to the published info, or schedules another attempt while the
    // address is still unknown. NsdUtils already delivers these callbacks on the main thread.
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

    // Any connectivity change can change the address the advertisement resolves to, so every event
    // simply schedules a debounced re-resolve on the main thread.
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

    // The Wi-Fi AP state affects which local address is reachable, so it also triggers a re-resolve.
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

    /**
     * Attaches the media stream wiring owned by the capture side.
     *
     * <p>Must be called on the main thread before {@link #start()}. The hub and runtime are shared
     * with {@link com.obby.android.localscreenshare.service.LssService}; this server only reads
     * them to serve stream calls and never owns their lifecycle.</p>
     */
    public void setStream(@NonNull final StreamHub hub, @NonNull final StreamRuntime runtime) {
        mStreamHub = hub;
        mStreamRuntime = runtime;
    }

    /** Sets the session-level secure flag applied to every descriptor. */
    public void setSecure(final boolean secure) {
        mSecure = secure;
    }

    /**
     * Binds the gRPC port and starts advertising the service.
     *
     * <p>Must be called once on the main thread. When this method returns, the gRPC server is
     * accepting calls and the info listener, if any, has already been notified with the initial
     * {@link LssServerInfo}. The host address in that first notification is
     * {@link Constants#UNKNOWN_HOST_ADDRESS} until NSD resolution succeeds.</p>
     *
     * @throws IOException if the port cannot be bound or the server cannot start
     */
    public void start() throws IOException {
        mGrpcServer.start();

        mServerInfo = LssServerInfo.builder()
            .id(Preferences.get().getServiceId())
            .name(Preferences.get().getServiceName())
            .hostAddress(Constants.UNKNOWN_HOST_ADDRESS)
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

    /**
     * Stops advertising, stops the gRPC server and releases the executor.
     *
     * <p>Must be called on the main thread. State is nulled first, so pending callbacks turn into
     * no-ops; then pending main-thread callbacks are removed, the broadcast receiver, the network
     * callback and the NSD registration are unregistered, and finally the gRPC server is shut down,
     * active client observers are released and the executor is terminated. Unregistering callbacks
     * that Android already released is tolerated by the surrounding {@code try} blocks.</p>
     */
    public void stop() {
        mIsStopped = true;
        mServerInfo = null;
        mNsdServiceInfo = null;
        mServerProfile = null;
        mServerStats = null;
        mMainHandler.removeCallbacksAndMessages(null);

        try {
            mContext.unregisterReceiver(mBroadcastReceiver);
        } catch (Exception e) {
            // ignored
        }

        try {
            mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
        } catch (Exception e) {
            // ignored
        }

        try {
            mNsdManager.unregisterService(mNsdRegistrationListener);
        } catch (Exception e) {
            // ignored
        }

        mGrpcServer.shutdownNow();
        mScreenStreamService.stop();
        mGrpcServerExecutor.shutdownNow();
    }

    /**
     * Publishes the latest captured frame to every connected client.
     *
     * <p>Typically called from the capture thread; safe to call from any thread. The frame replaces
     * the previous one until a client is ready to receive it, so a slow client drops intermediate
     * frames instead of queueing them.</p>
     *
     * @param frame newest frame; the server does not mutate it
     */
    public void postScreenFrame(@NonNull final ScreenFrame frame) {
        mScreenStreamService.postScreenFrame(frame);
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

    /**
     * gRPC service implementation that fans the newest frame out to all connected clients.
     *
     * <p>Service calls run on the gRPC server executor. Registration and frame publication are
     * serialized by {@code mLock}; {@link #stop()} marks the service stopped, releases every client
     * observer and clears the frame while holding the same lock, so no frame is published after
     * shutdown starts. Observers remove themselves through their release callback, which re-enters
     * the same monitor from the releasing thread.</p>
     */
    private static class ScreenStreamService extends ScreenStreamServiceGrpc.ScreenStreamServiceImplBase {
        private volatile boolean mIsStopped;

        private volatile ScreenFrame mScreenFrame;

        @NonNull
        private final Executor mExecutor;

        @NonNull
        private final Object mLock = new Object();

        // Copy-on-write so fan-out can iterate while an observer removes itself on release.
        @NonNull
        private final List<ScreenStreamResponseObserver> mScreenStreamResponseObservers = new CopyOnWriteArrayList<>();

        private ScreenStreamService(@NonNull final Executor executor) {
            mExecutor = executor;
        }

        /**
         * Registers a client and immediately sends the latest frame, if one is already available.
         * Runs on the gRPC server executor.
         */
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
                    observer.postScreenFrame(mScreenFrame);
                }
            }
        }

        /**
         * Stores the newest frame and offers it to every connected client; called from any thread.
         */
        public void postScreenFrame(@NonNull final ScreenFrame frame) {
            if (mIsStopped) {
                return;
            }

            synchronized (mLock) {
                if (mIsStopped) {
                    return;
                }

                mScreenFrame = frame;
                mScreenStreamResponseObservers.forEach(observer -> observer.postScreenFrame(mScreenFrame));
            }
        }

        /**
         * Marks the service stopped, releases every client and drops the retained frame. Called by
         * the server's stop() after the gRPC server has been shut down.
         */
        public void stop() {
            synchronized (mLock) {
                mIsStopped = true;
                mScreenFrame = null;
                mScreenStreamResponseObservers.forEach(ScreenStreamResponseObserver::release);
                mScreenStreamResponseObservers.clear();
            }
        }
    }

    /**
     * gRPC service implementation for the bidirectional media stream.
     *
     * <p>Each call creates a {@link StreamCallHandler} that joins the shared stream hub and drives
     * one per-client drain thread. The inbound observer only forwards client control messages to
     * the handler; media is pushed out by the drain thread through the handler's transport, so a
     * slow client never blocks gRPC callbacks or the encoder.</p>
     */
    private final class StreamService extends ScreenStreamServiceGrpc.ScreenStreamServiceImplBase {
        @Override
        public StreamObserver<ClientMessage> stream(StreamObserver<ServerMessage> responseObserver) {
            if (mIsStopped || mStreamHub == null || mStreamRuntime == null) {
                responseObserver.onCompleted();
                return new NoopClientObserver();
            }
            final StreamCallHandler handler = new StreamCallHandler(mStreamHub, mStreamRuntime,
                    new GrpcMediaTransport(responseObserver, mSecure));
            final Thread drain = new Thread(() -> drainLoop(handler, responseObserver),
                    "lss-stream-drain-" + handler.hashCode());
            drain.setDaemon(true);
            drain.start();
            return new ClientObserver(handler);
        }

        private void drainLoop(StreamCallHandler handler,
                               StreamObserver<ServerMessage> responseObserver) {
            while (!handler.isClosed()) {
                try {
                    if (!handler.drainOnce()) {
                        LockSupport.parkNanos(5_000_000L);
                    }
                } catch (IOException | RuntimeException e) {
                    handler.close();
                    try {
                        responseObserver.onError(Status.INTERNAL
                                .withDescription(e.getMessage())
                                .asRuntimeException());
                    } catch (RuntimeException ignored) {
                        // the observer was already completed
                    }
                    return;
                }
            }
        }
    }

    /** Forwards client control messages to a handler and closes it when the call ends. */
    private static final class ClientObserver implements StreamObserver<ClientMessage> {
        private final StreamCallHandler handler;

        ClientObserver(StreamCallHandler handler) {
            this.handler = handler;
        }

        @Override
        public void onNext(ClientMessage message) {
            try {
                handler.onClientMessage(message);
            } catch (RuntimeException e) {
                handler.close();
            }
        }

        @Override
        public void onError(Throwable t) {
            handler.close();
        }

        @Override
        public void onCompleted() {
            handler.close();
        }
    }

    /** Discards every client message; used once the server has stopped. */
    private static final class NoopClientObserver implements StreamObserver<ClientMessage> {
        @Override
        public void onNext(ClientMessage value) {
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }
    }

    /**
     * Per-client stream state. At most one frame is queued: a newer frame replaces the queued one,
     * and the handler sends only when the gRPC call is ready. The timestamp check prevents sending
     * the same frame again when readiness is signalled more than once.
     *
     * <p>The ready handler and {@link #postScreenFrame} are serialized by {@code mLock}; the gRPC
     * close and cancel callbacks, as well as {@link #release()}, may arrive on gRPC threads.
     * Release changes state only once and is also invoked when the enclosing service stops.</p>
     */
    @Accessors(prefix = "m")
    private static class ScreenStreamResponseObserver {
        private volatile boolean mIsReleased;

        private volatile ScreenFrame mScreenFrame;

        private volatile long mScreenFrameTimestamp = -1L;

        @Setter
        @Nullable
        private OnReleaseListener mOnReleaseListener;

        @NonNull
        private final ServerCallStreamObserver<ScreenFrame> mObserver;

        @NonNull
        private final Executor mExecutor;

        @NonNull
        private final Object mLock = new Object();

        // Sends the queued frame if it is newer than the last one sent; runs on mExecutor.
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

                    if (mScreenFrameTimestamp < mScreenFrame.getTimestamp()) {
                        mScreenFrameTimestamp = mScreenFrame.getTimestamp();
                        mObserver.onNext(mScreenFrame);
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

        /**
         * Offers a frame to this client. Only the newest frame is kept; when the call is ready the
         * send is scheduled on the executor, otherwise the frame waits for the ready callback.
         */
        public void postScreenFrame(@NonNull final ScreenFrame frame) {
            if (mIsReleased) {
                return;
            }

            synchronized (mLock) {
                if (mIsReleased) {
                    return;
                }

                mScreenFrame = frame;
                if (mObserver.isReady()) {
                    mExecutor.execute(mOnReadyHandler);
                }
            }
        }

        /**
         * Marks this client released and notifies the enclosing service, which removes it from the
         * fan-out list. State changes once; extra calls only repeat the notification.
         */
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

    /**
     * Aggregated transport accounting while the server is running.
     *
     * <p>{@code mLock} guards the transport list and the collection timestamp; byte counters are
     * atomics updated from gRPC transport threads. {@link #collect()} runs on the main thread,
     * resets per-transport counters and derives rates over the elapsed interval.</p>
     */
    @Accessors(prefix = "m")
    private static class ServerProfile {
        @NonNull
        private final Object mLock = new Object();

        @NonNull
        private final AtomicLong mCollectionTimestamp = new AtomicLong(SystemClock.elapsedRealtimeNanos());

        // Copy-on-write; the stream tracer reads this without taking mLock.
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
                // getAndSet closes the current interval and opens the next one.
                final long startTimestamp = mCollectionTimestamp.getAndSet(SystemClock.elapsedRealtimeNanos());
                final long endTimestamp = mCollectionTimestamp.get();
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

        /**
         * Counters for one remote address seen while the server is running.
         */
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
