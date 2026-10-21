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

import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.stream.SessionSnapshot;
import com.obby.android.localscreenshare.core.stream.StreamHub;
import com.obby.android.localscreenshare.core.stream.StreamRuntime;
import com.obby.android.localscreenshare.core.util.TimeSource;
import com.obby.android.localscreenshare.discovery.favorite.FavoriteStore;
import com.obby.android.localscreenshare.discovery.model.AuthMode;
import com.obby.android.localscreenshare.discovery.model.DeviceAddress;
import com.obby.android.localscreenshare.discovery.model.DevicePresence;
import com.obby.android.localscreenshare.discovery.model.DeviceRole;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.model.JoinPolicy;
import com.obby.android.localscreenshare.discovery.model.DeviceInfo;
import com.obby.android.localscreenshare.discovery.model.ShareState;
import com.obby.android.localscreenshare.discovery.nsd.NsdConstants;
import com.obby.android.localscreenshare.discovery.nsd.NsdResolver;
import com.obby.android.localscreenshare.discovery.nsd.NsdTxtRecord;
import com.obby.android.localscreenshare.discovery.register.GrpcRegisterStub;
import com.obby.android.localscreenshare.discovery.register.RegisterClient;
import com.obby.android.localscreenshare.discovery.register.RegisterProtoMapper;
import com.obby.android.localscreenshare.discovery.store.DeviceStore;
import com.obby.android.localscreenshare.grpc.screenstream.ClientMessage;
import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterRequest;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterResponse;
import com.obby.android.localscreenshare.grpc.screenstream.ScreenStreamServiceGrpc;
import com.obby.android.localscreenshare.grpc.screenstream.ServerMessage;
import com.obby.android.localscreenshare.identity.DeviceIdentity;
import com.obby.android.localscreenshare.identity.IdentityManager;
import com.obby.android.localscreenshare.identity.IdentityUnavailableException;
import com.obby.android.localscreenshare.identity.tls.TlsCertificate;
import com.obby.android.localscreenshare.identity.tls.TlsMaterial;
import com.obby.android.localscreenshare.identity.tls.TlsMaterialHolder;
import com.obby.android.localscreenshare.settings.DiscoverySettings;
import com.obby.android.localscreenshare.settings.Preferences;
import com.obby.android.localscreenshare.trust.PeerIdentityMapper;
import com.obby.android.localscreenshare.trust.PinPolicy;
import com.obby.android.localscreenshare.trust.PinningTrustManager;
import com.obby.android.localscreenshare.trust.TlsPin;
import com.obby.android.localscreenshare.trust.TlsPinStore;
import com.obby.android.localscreenshare.settings.VisibilityPolicy;
import com.obby.android.localscreenshare.support.BackoffPolicy;
import com.obby.android.localscreenshare.support.Constants;
import com.obby.android.localscreenshare.support.DelayedScheduler;
import com.obby.android.localscreenshare.support.NetUtils;
import com.obby.android.localscreenshare.support.ServiceProtocol;
import com.obby.android.localscreenshare.support.ThreadUtils;
import com.obby.android.localscreenshare.transport.GrpcConstants;
import com.obby.android.localscreenshare.transport.GrpcMediaTransport;
import com.obby.android.localscreenshare.transport.ScreenStreamProtoMapper;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import io.grpc.Attributes;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCredentials;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerStreamTracer;
import io.grpc.ServerTransportFilter;
import io.grpc.Status;
import io.grpc.TlsServerCredentials;
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
 * bind a port. {@link #start()} binds the configured port, registers both NSD service types, the
 * default-network callback and the Wi-Fi AP state receiver, publishes an initial
 * {@link ServerInfo} with a placeholder host address, and starts the periodic stats sampling.
 * {@link #stop()} releases all of them in reverse order and shuts the gRPC executor down; late
 * NSD, network and gRPC callbacks then observe nulled state and become no-ops.</p>
 *
 * <p><b>Advertisement.</b> The primary registration uses the {@code _lss._tcp} type with the
 * minimal TXT record; the legacy {@code _localscreenshare._udp} registration stays in place for
 * clients that only know the old type. The instance name is {@code LSS-<id first 8>}, and the
 * device name is only written when the user enabled name sharing. Registration and resolve
 * failures are retried with {@link BackoffPolicy}; both types are retried independently so one
 * failing type never unregisters the other.</p>
 *
 * <p><b>Host address discovery.</b> The host address is not known when the gRPC port is bound. The
 * server resolves its own NSD registration and re-resolves it whenever the default network
 * changes, a network capability changes or the Wi-Fi AP state changes; resolve failures are
 * retried with a bounded timeout and a backoff. {@link ServerInfoListener} is notified whenever
 * the resulting address changes.</p>
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
public final class ScreenShareServer {
    private static final long UPDATE_SERVER_STATS_INTERVAL_MS = 3000L;

    // Upper bound of the wait for the gRPC server to terminate during stop, before the callback
    // executor is shut down.
    private static final long GRPC_STOP_TIMEOUT_MS = 2_000L;

    // Schema version of the discovery metadata written into the NSD TXT record.
    private static final int NSD_METADATA_VERSION = 1;

    // Remote address of the current call, published by the interceptor below so the unary
    // Register handler can rate-limit per caller without reaching into gRPC internals.
    private static final io.grpc.Context.Key<InetSocketAddress> REMOTE_ADDRESS =
        io.grpc.Context.key("lss-remote-address");

    private static final ServerInterceptor REMOTE_ADDRESS_INTERCEPTOR = new ServerInterceptor() {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(final ServerCall<ReqT, RespT> call,
            final Metadata headers, final ServerCallHandler<ReqT, RespT> next) {
            final InetSocketAddress remoteAddress =
                (InetSocketAddress) call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
            return Contexts.interceptCall(
                io.grpc.Context.current().withValue(REMOTE_ADDRESS, remoteAddress), call, headers, next);
        }
    };

    // Published identity; null while stopped.
    @Nullable
    private ServerInfo mServerInfo;

    // Primary registration used for resolve and re-register attempts; null while stopped.
    @Nullable
    private NsdServiceInfo mNsdServiceInfo;

    // One registration per service type: the current type and the legacy type kept for old clients.
    @Nullable
    private Registration mPrimaryRegistration;

    @Nullable
    private Registration mLegacyRegistration;

    // Bounds re-resolve attempts after a failure; reset on success and on network changes.
    @NonNull
    private final BackoffPolicy mResolveBackoff = BackoffPolicy.defaults();

    // Per-transport accounting; null while stopped.
    @Nullable
    private ServerProfile mServerProfile;

    // Last sampled value; used to suppress duplicate listener calls.
    @Nullable
    private ServerStats mServerStats;

    @Setter
    @Nullable
    private ServerInfoListener mServerInfoListener;

    @Setter
    @Nullable
    private ServerStatsListener mServerStatsListener;

    // Media stream wiring: the hub owns per-client send sessions and the runtime owns the encoder.
    // Both are assembled and started by ScreenShareService before the server accepts streams.
    @Nullable
    private StreamHub mStreamHub;

    @Nullable
    private StreamRuntime mStreamRuntime;

    // Live stream calls; used to attribute a session's counters to the remote address its call
    // arrived on. Closed handlers are pruned on every statistics collection.
    @NonNull
    private final List<StreamCallHandler> mStreamCallHandlers = new CopyOnWriteArrayList<>();

    // Store shared with the activity-side discovery, so a peer learned through Register shows up
    // in the device list. Optional: a server without it simply does not learn callers.
    @Nullable
    private DeviceStore mDeviceStore;

    // Built by start() and cleared by stop(); handles unary Register calls.
    @Nullable
    private RegisterCallHandler mRegisterHandler;

    // Reverse Register of the discovery handshake; stopped with the server.
    @Nullable
    private RegisterClient mReverseRegisterClient;

    @Nullable
    private DelayedScheduler mRegisterScheduler;

    // Session-level secure flag applied to every descriptor; set by ScreenShareService.
    private volatile boolean mSecure;

    // Set by stop(); late stream calls become no-ops.
    private volatile boolean mIsStopped;

    private final String mTag = "ScreenShareServer@" + hashCode();

    @NonNull
    private final Context mContext;

    @NonNull
    private final NsdManager mNsdManager;

    @NonNull
    private final ConnectivityManager mConnectivityManager;

    @NonNull
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // Stream-call lifecycle events that are worth a log line. Kept separate from StreamCallHandler
    // because that handler runs in JVM unit tests, where android.util.Log is not available.
    @NonNull
    private final StreamCallListener mStreamCallListener = new StreamCallListener() {
        @Override
        public void onSessionTakenOver(final ClientSessionKey key, final String transportId) {
            Log.i(mTag, String.format("stream: session taken over"
                + ", streamKey = %s, clientId = %s, transport = %s",
                key.getStreamKey().getValue(), key.getClientId(), transportId));
        }

        @Override
        public void onJoinRejected(final ClientSessionKey key, final String transportId,
                                   final String reason) {
            Log.w(mTag, String.format("stream: join rejected"
                + ", streamKey = %s, clientId = %s, transport = %s, reason = %s",
                key.getStreamKey().getValue(), key.getClientId(), transportId, reason));
        }
    };

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
        .build(), GrpcServerShutdown.rejectionHandler());

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

    // Built by start() after the identity is available and shut down by stop(); null while
    // stopped. Constructing it here would open the key store in a field initializer.
    @Nullable
    private Server mGrpcServer;

    // True when the gRPC port is served over TLS; advertised as the discovery secure flag.
    private volatile boolean mTransportSecure = true;

    // Re-resolves the own registration. Every caller goes through postResolveNsdServiceRunnable(),
    // which coalesces pending requests into one main-thread run.
    @NonNull
    private final Runnable mResolveNsdServiceRunnable = new Runnable() {
        @Override
        public void run() {
            if (mNsdServiceInfo != null) {
                NsdResolver.resolveService(mNsdManager, mNsdServiceInfo, NsdResolver.DEFAULT_RESOLVE_TIMEOUT_MS,
                    mNsdResolveListener);
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

            final ServerStats serverStats = mServerProfile.collect(sessions(),
                clientAddresses());
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

    /**
     * NSD registration for one service type.
     *
     * <p>Each type owns its listener and its backoff schedule, so a failed registration only
     * retries that type and never unregisters the type that is already published. Registration
     * failures are retried on the main thread; a successful registration clears the schedule and
     * (for the primary type) triggers the address resolve.</p>
     */
    private final class Registration implements NsdManager.RegistrationListener {
        @NonNull
        private final String mServiceType;

        @NonNull
        private final NsdServiceInfo mServiceInfo;

        // Only the primary type resolves its own registration to learn the reachable address.
        private final boolean mResolvesAddress;

        @NonNull
        private final BackoffPolicy mBackoff = BackoffPolicy.defaults();

        @NonNull
        private final Runnable mRetryRunnable = this::register;

        private boolean mUnregistered;

        private Registration(@NonNull final String serviceType, @NonNull final NsdServiceInfo serviceInfo,
            final boolean resolvesAddress) {
            mServiceType = serviceType;
            mServiceInfo = serviceInfo;
            mResolvesAddress = resolvesAddress;
        }

        private void register() {
            if (mServerInfo == null || mUnregistered) {
                return;
            }
            try {
                mNsdManager.registerService(mServiceInfo, NsdManager.PROTOCOL_DNS_SD, this);
                Log.i(mTag, String.format("Registration.register: registration requested, serviceType = %s",
                    mServiceType));
            } catch (RuntimeException e) {
                Log.e(mTag, String.format("Registration.register: registration rejected, serviceType = %s",
                    mServiceType), e);
                postRetry();
            }
        }

        private void unregister() {
            mUnregistered = true;
            mMainHandler.removeCallbacks(mRetryRunnable);
            try {
                mNsdManager.unregisterService(this);
            } catch (RuntimeException e) {
                // The framework already dropped the registration; nothing to release.
            }
        }

        // Must run on the main thread: it reads server state and touches the main handler.
        private void postRetry() {
            if (mServerInfo == null || mUnregistered) {
                return;
            }
            final long delayMs = mBackoff.nextDelayMs();
            Log.i(mTag, String.format("Registration.postRetry: retry scheduled, serviceType = %s, delayMs = %d",
                mServiceType, delayMs));
            mMainHandler.removeCallbacks(mRetryRunnable);
            mMainHandler.postDelayed(mRetryRunnable, delayMs);
        }

        @Override
        public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            Log.e(mTag, String.format("onRegistrationFailed: serviceType = %s, serviceInfo = %s, errorCode = %d",
                mServiceType, serviceInfo, errorCode));
            mMainHandler.post(this::postRetry);
        }

        @SuppressWarnings("SpellCheckingInspection")
        @Override
        public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            Log.e(mTag, String.format("onUnregistrationFailed: serviceType = %s, serviceInfo = %s, errorCode = %d",
                mServiceType, serviceInfo, errorCode));
        }

        @Override
        public void onServiceRegistered(NsdServiceInfo serviceInfo) {
            Log.i(mTag, String.format("onServiceRegistered: serviceType = %s, serviceInfo = %s",
                mServiceType, serviceInfo));
            mMainHandler.post(() -> {
                if (mServerInfo == null || mUnregistered) {
                    return;
                }
                mBackoff.reset();
                if (mResolvesAddress) {
                    mResolveBackoff.reset();
                    postResolveNsdServiceRunnable();
                }
            });
        }

        @Override
        public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
            Log.i(mTag, String.format("onServiceUnregistered: serviceType = %s, serviceInfo = %s",
                mServiceType, serviceInfo));
        }
    }

    // Applies a resolved host address to the published info, or schedules another attempt while the
    // address is still unknown. NsdResolver already delivers these callbacks on the main thread.
    @NonNull
    private final NsdManager.ResolveListener mNsdResolveListener = new NsdManager.ResolveListener() {
        @Override
        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
            Log.e(mTag, String.format("mNsdResolveListener.onResolveFailed: service resolve failed, serviceInfo = %s"
                + ", errorCode = %d", serviceInfo, errorCode));
            postResolveRetry();
        }

        @Override
        public void onServiceResolved(NsdServiceInfo serviceInfo) {
            final String hostAddress = Optional.ofNullable(serviceInfo.getHost())
                .map(NetUtils::getHostAddress)
                .orElse(null);
            Log.i(mTag, String.format("mNsdResolveListener.onServiceResolved: service resolved, serviceInfo = %s"
                + ", hostAddress = %s", serviceInfo, hostAddress));

            if (TextUtils.isEmpty(hostAddress)) {
                postResolveRetry();
            } else {
                mResolveBackoff.reset();
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
            if (ServiceProtocol.ACTION_WIFI_AP_STATE_CHANGED.equals(intent.getAction())) {
                postResolveNsdServiceRunnable();
            }
        }
    };

    public ScreenShareServer(@NonNull final Context context) {
        mContext = context;
        mNsdManager = mContext.getSystemService(NsdManager.class);
        mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
    }

    /**
     * Attaches the media stream wiring owned by the capture side.
     *
     * <p>Must be called on the main thread before {@link #start()}. The hub and runtime are shared
     * with {@link com.obby.android.localscreenshare.service.ScreenShareService}; this server only reads
     * them to serve stream calls and never owns their lifecycle.</p>
     */
    public void setStream(@NonNull final StreamHub hub, @NonNull final StreamRuntime runtime) {
        mStreamHub = hub;
        mStreamRuntime = runtime;
    }

    /**
     * Sets the session-level secure flag applied to every descriptor.
     */
    public void setSecure(final boolean secure) {
        mSecure = secure;
    }

    /**
     * Attaches the device store shared with the activity-side discovery.
     *
     * <p>Must be called on the main thread before {@link #start()}. With a store, a peer learned
     * through Register is written as a confirmed device and appears in the same list the UI
     * renders; without one, Register calls are still answered but nothing is learned.</p>
     */
    public void setDeviceStore(@NonNull final DeviceStore deviceStore) {
        mDeviceStore = deviceStore;
    }

    // Builds and binds the gRPC server. TLS material is loaded here, never in a field initializer:
    // opening the key store during construction is the pattern that crashed startup before (D-1).
    private void startGrpcServer() throws IOException {
        final boolean isPlaintext = Preferences.get().isTlsAllowPlaintext();
        final ServerCredentials credentials;
        if (isPlaintext) {
            credentials = InsecureServerCredentials.create();
        } else {
            try {
                final TlsMaterial material = TlsMaterialHolder.get().material();
                credentials = TlsServerCredentials.newBuilder()
                    .keyManager(material.getKeyManager())
                    .trustManager(new PinningTrustManager(null, TlsPinStore.get(), true,
                        PinningTrustManager.Role.SERVER))
                    .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
                    .build();
            } catch (IdentityUnavailableException e) {
                throw new IOException("TLS material unavailable", e);
            }
        }

        mGrpcServer = OkHttpServerBuilder
            .forPort(Preferences.get().getServerPort(), credentials)
            .flowControlWindow(GrpcConstants.GRPC_FLOW_CONTROL_WINDOW)
            .maxInboundMessageSize(GrpcConstants.GRPC_MAX_INBOUND_MESSAGE_SIZE)
            .executor(mGrpcServerExecutor)
            .addTransportFilter(mServerTransportFilter)
            .addStreamTracerFactory(mServerStreamTracerFactory)
            .intercept(REMOTE_ADDRESS_INTERCEPTOR)
            .intercept(new TlsSessionInterceptor())
            .addService(mStreamService)
            .build();
        mTransportSecure = !isPlaintext;
        mGrpcServer.start();
    }

    @NonNull
    private Server grpcServer() {
        final Server server = mGrpcServer;
        if (server == null) {
            throw new IllegalStateException("gRPC server is not running");
        }
        return server;
    }

    /**
     * Binds the gRPC port and starts advertising the service.
     *
     * <p>Must be called once on the main thread. When this method returns, the gRPC server is
     * accepting calls and the info listener, if any, has already been notified with the initial
     * {@link ServerInfo}. The host address in that first notification is
     * {@link Constants#UNKNOWN_HOST_ADDRESS} until NSD resolution succeeds.</p>
     *
     * @throws IOException if the port cannot be bound or the server cannot start
     */
    public void start() throws IOException {
        startGrpcServer();

        // The identity comes from the AndroidKeyStore, never from a stored value; a changed key
        // shows up as a changed fingerprint on the peers that stored it.
        final DeviceIdentity identity;
        try {
            identity = IdentityManager.get().getIdentity();
        } catch (IdentityUnavailableException e) {
            throw new IOException("device identity unavailable", e);
        }

        mServerInfo = ServerInfo.builder()
            .id(identity.getId())
            .deviceId(identity.getId())
            .fingerprint(identity.getFingerprint())
            .name(Preferences.get().getServiceName())
            .hostAddress(Constants.UNKNOWN_HOST_ADDRESS)
            .port(grpcServer().getPort())
            .build();

        final DeviceInfo advertisement = buildAdvertisement(mServerInfo);
        // Compatibility mode publishes only the legacy type, so a legacy peer sees this device
        // and this device never advertises a record that requires the new TXT keys.
        final DiscoverySettings settings = Preferences.get().getDiscoverySettings();
        mPrimaryRegistration = settings.isCompatOnly() ? null
            : new Registration(NsdConstants.NSD_SERVICE_TYPE,
                buildNsdServiceInfo(advertisement, NsdTxtRecord.Namespace.CURRENT), true);
        mLegacyRegistration = new Registration(NsdConstants.NSD_SERVICE_TYPE_LEGACY,
            buildNsdServiceInfo(advertisement, NsdTxtRecord.Namespace.LEGACY),
            settings.isCompatOnly());
        mNsdServiceInfo = mPrimaryRegistration != null
            ? mPrimaryRegistration.mServiceInfo : mLegacyRegistration.mServiceInfo;

        if (mPrimaryRegistration != null) {
            mPrimaryRegistration.register();
        }
        mLegacyRegistration.register();
        mConnectivityManager.registerDefaultNetworkCallback(mNetworkCallback);

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ServiceProtocol.ACTION_WIFI_AP_STATE_CHANGED);
        ContextCompat.registerReceiver(mContext, mBroadcastReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);

        if (mServerInfoListener != null) {
            mServerInfoListener.onServerInfoChanged(mServerInfo);
        }

        mServerProfile = new ServerProfile();
        mMainHandler.postDelayed(mUpdateServerStatsRunnable, UPDATE_SERVER_STATS_INTERVAL_MS);

        // Register is served on the same channel as the stream; the reverse call is only made
        // when a caller announced itself, and it never announces again.
        mRegisterScheduler = DelayedScheduler.newSingleThread("lss-register-reverse");
        mReverseRegisterClient = new RegisterClient(new GrpcRegisterStub(), mRegisterScheduler,
            ThreadUtils.getMainThreadExecutor());
        mRegisterHandler = new RegisterCallHandler(this::buildDeviceSummary, mDeviceStore,
            ThreadUtils.getMainThreadExecutor(), TimeSource.MONOTONIC, mReverseRegisterClient::register,
            mRegisterScheduler, new Random(), DiscoveryChannel.MULTICAST,
            this::acceptsRegisterFrom);
    }

    /**
     * Applies the local visibility preset to an incoming Register.
     *
     * <p>Runs on the gRPC thread; both preference stores are thread-safe. A hidden device accepts
     * nobody, a favorites-only device accepts a caller it already saved, and the visible presets
     * accept every validated caller.</p>
     */
    private boolean acceptsRegisterFrom(@NonNull final String deviceId) {
        final long nowMs = System.currentTimeMillis();
        final VisibilityPolicy policy = Preferences.get().getDiscoverySettings().visibilityPolicy(nowMs);
        return policy.acceptsRegister(nowMs, FavoriteStore.get().isFavorite(deviceId));
    }

    /**
     * Re-publishes the mDNS record after a discovery setting changed.
     *
     * <p>Called on the main thread from the capture service's preference observer. The gRPC server
     * and the running stream are untouched: only the two NSD registrations are replaced, because
     * compatibility mode drops the current service type and the TXT record carries the join policy
     * and the optional display name. The old registrations are released before the new ones are
     * created, so a listener is never registered twice.</p>
     */
    public void onDiscoverySettingsChanged() {
        if (mServerInfo == null) {
            return;
        }
        Log.i(mTag, "onDiscoverySettingsChanged: re-publish the mDNS record");

        if (mPrimaryRegistration != null) {
            mPrimaryRegistration.unregister();
            mPrimaryRegistration = null;
        }
        if (mLegacyRegistration != null) {
            mLegacyRegistration.unregister();
            mLegacyRegistration = null;
        }

        final DeviceInfo advertisement = buildAdvertisement(mServerInfo);
        final DiscoverySettings settings = Preferences.get().getDiscoverySettings();
        mPrimaryRegistration = settings.isCompatOnly() ? null
            : new Registration(NsdConstants.NSD_SERVICE_TYPE,
                buildNsdServiceInfo(advertisement, NsdTxtRecord.Namespace.CURRENT), true);
        mLegacyRegistration = new Registration(NsdConstants.NSD_SERVICE_TYPE_LEGACY,
            buildNsdServiceInfo(advertisement, NsdTxtRecord.Namespace.LEGACY),
            settings.isCompatOnly());
        mNsdServiceInfo = mPrimaryRegistration != null
            ? mPrimaryRegistration.mServiceInfo : mLegacyRegistration.mServiceInfo;

        if (mPrimaryRegistration != null) {
            mPrimaryRegistration.register();
        }
        mLegacyRegistration.register();
    }

    /**
     * Stops advertising, stops the gRPC server and releases the executor.
     *
     * <p>Must be called on the main thread. State is nulled first, so pending callbacks turn into
     * no-ops; then pending main-thread callbacks are removed, the broadcast receiver, the network
     * callback and both NSD registrations are unregistered, and finally the gRPC server is shut
     * down, active client observers are released and the executor is terminated. Unregistering
     * callbacks that Android already released is tolerated by the surrounding {@code try}
     * blocks.</p>
     */
    public void stop() {
        mIsStopped = true;
        mServerInfo = null;
        mNsdServiceInfo = null;
        mServerProfile = null;
        mServerStats = null;
        mRegisterHandler = null;
        mStreamCallHandlers.clear();
        if (mReverseRegisterClient != null) {
            mReverseRegisterClient.cancelAll();
            mReverseRegisterClient = null;
        }
        if (mRegisterScheduler != null) {
            mRegisterScheduler.shutdown();
            mRegisterScheduler = null;
        }
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

        // Both registrations are released here; each type is also retried independently while
        // running, so a failed type never unregisters the type that is published.
        if (mPrimaryRegistration != null) {
            mPrimaryRegistration.unregister();
            mPrimaryRegistration = null;
        }
        if (mLegacyRegistration != null) {
            mLegacyRegistration.unregister();
            mLegacyRegistration = null;
        }

        // Shut the server down first and let its transports finish before terminating the callback
        // executor; a transport's close posts a context-closer task to that executor, and a
        // rejecting executor would crash the process here while a client is streaming.
        final Server server = mGrpcServer;
        mGrpcServer = null;
        if (server != null) {
            GrpcServerShutdown.shutdown(new GrpcServerShutdown.ServerHandle() {
                @Override
                public void shutdownNow() {
                    server.shutdownNow();
                }

                @Override
                public boolean awaitTermination(long timeoutMs) throws InterruptedException {
                    return server.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
                }
            }, mGrpcServerExecutor, GRPC_STOP_TIMEOUT_MS);
        }
    }

    private void updateServerInfo(@NonNull final ServerInfo serverInfo) {
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

    // Retries a failed resolve after a backoff delay instead of re-posting immediately, so a
    // permanently failing resolve cannot spin the main thread.
    private void postResolveRetry() {
        if (mServerInfo == null) {
            return;
        }
        final long delayMs = mResolveBackoff.nextDelayMs();
        Log.i(mTag, String.format("postResolveRetry: resolve retry scheduled, delayMs = %d", delayMs));
        mMainHandler.removeCallbacks(mResolveNsdServiceRunnable);
        mMainHandler.postDelayed(mResolveNsdServiceRunnable, delayMs);
    }

    /**
     * Returns the attached send sessions, ordered by client id; empty before a stream is wired.
     */
    @NonNull
    private List<SessionSnapshot> sessions() {
        final StreamHub hub = mStreamHub;
        return hub == null ? Collections.emptyList() : hub.snapshotSessions();
    }

    /**
     * Returns the remote address of every attached session, keyed by client id.
     *
     * <p>Only live calls contribute: a handler that has not attached yet has no session key, and a
     * closed handler is pruned here rather than on its own drain thread, because that thread has
     * already left when the call ended. The address is the one the call arrived on, in the same
     * socket form the transport tracer uses for the byte counters, so both can be joined.</p>
     */
    @NonNull
    private Map<String, String> clientAddresses() {
        final Map<String, String> addresses = new HashMap<>();
        for (final StreamCallHandler handler : mStreamCallHandlers) {
            if (handler.isClosed()) {
                mStreamCallHandlers.remove(handler);
                continue;
            }
            final ClientSessionKey key = handler.getAttachedKey();
            final String address = handler.getRemoteAddress();
            if (key != null && address != null) {
                addresses.putIfAbsent(key.getClientId(), address);
            }
        }
        return addresses;
    }

    /**
     * Returns this device's Register summary, or null while the server is stopping.
     *
     * <p>The summary carries the identity, the advertised flags and the reachable gRPC address.
     * The address is omitted while NSD has not resolved one, because a placeholder host would
     * make a peer call the wrong destination.</p>
     */
    @Nullable
    private DeviceSummary buildDeviceSummary() {
        final ServerInfo serverInfo = mServerInfo;
        if (serverInfo == null) {
            return null;
        }
        final List<DeviceAddress> addresses = new ArrayList<>(1);
        final String hostAddress = serverInfo.getHostAddress();
        if (hostAddress != null && !hostAddress.trim().isEmpty()
            && !Constants.UNKNOWN_HOST_ADDRESS.equals(hostAddress) && serverInfo.getPort() > 0) {
            addresses.add(new DeviceAddress(hostAddress, serverInfo.getPort(), DiscoveryChannel.MULTICAST));
        }

        final DeviceInfo device = DeviceInfo.builder()
            .id(serverInfo.getDeviceId())
            .fingerprint(serverInfo.getFingerprint())
            .name(Preferences.get().isShareNameEnabled() ? Preferences.get().getDeviceAlias() : null)
            .role(DeviceRole.SERVER)
            .state(ShareState.LIVE)
            .join(JoinPolicy.OPEN)
            .secure(mSecure)
            .auth(AuthMode.NONE)
            .protocolVersion(String.valueOf(ScreenStreamProtoMapper.PROTOCOL_VERSION))
            .metadataVersion(NSD_METADATA_VERSION)
            .capabilities(NsdConstants.DEVICE_CAPABILITY_H264 | NsdConstants.DEVICE_CAPABILITY_JPEG)
            .addresses(addresses)
            .build();
        return RegisterProtoMapper.toSummary(device);
    }

    // Remote host of the current Register call; the interceptor publishes it, and an unknown
    // address is rate-limited under one shared key instead of being rejected.
    @NonNull
    private static String remoteHost() {
        final InetSocketAddress remoteAddress = REMOTE_ADDRESS.get();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return "unknown";
        }
        return NetUtils.getHostAddress(remoteAddress.getAddress());
    }

    @NonNull
    private DeviceInfo buildAdvertisement(@NonNull final ServerInfo serverInfo) {        // The fingerprint is deliberately absent: it never goes into the mDNS record and travels
        // with the application-level handshake (Register and HELLO) instead.
        final DiscoverySettings settings = Preferences.get().getDiscoverySettings();
        final long nowMs = System.currentTimeMillis();
        return DeviceInfo.builder()
            .id(serverInfo.getDeviceId())
            .name(Preferences.get().getDeviceAlias())
            .role(DeviceRole.SERVER)
            .state(ShareState.LIVE)
            .join(settings.visibilityPolicy(nowMs).joinPolicy(nowMs))
            .secure(mSecure)
            .auth(AuthMode.NONE)
            .protocolVersion(String.valueOf(ScreenStreamProtoMapper.PROTOCOL_VERSION))
            .metadataVersion(NSD_METADATA_VERSION)
            .capabilities(settings.capabilities())
            .addresses(Collections.emptyList())
            .presence(DevicePresence.HEARD)
            .build();
    }

    /**
     * Builds one advertisement. The instance name is stable and readable but reveals nothing beyond
     * a prefix of the device id: it is {@code LSS-<id first 8>}. The TXT record is minimal, never
     * contains the fingerprint, and never contains the system device name unless the user enabled
     * name sharing.
     */
    @NonNull
    private NsdServiceInfo buildNsdServiceInfo(@NonNull final DeviceInfo advertisement,
        @NonNull final NsdTxtRecord.Namespace namespace) {
        final NsdServiceInfo nsdServiceInfo = new NsdServiceInfo();
        nsdServiceInfo.setServiceName(DeviceInfo.NAME_PREFIX + advertisement.shortId());
        nsdServiceInfo.setServiceType(namespace == NsdTxtRecord.Namespace.LEGACY
            ? NsdConstants.NSD_SERVICE_TYPE_LEGACY : NsdConstants.NSD_SERVICE_TYPE);
        nsdServiceInfo.setPort(grpcServer().getPort());

        final Map<String, byte[]> attributes =
            NsdTxtRecord.encode(advertisement, namespace, Preferences.get().isShareNameEnabled());
        for (final Map.Entry<String, byte[]> attribute : attributes.entrySet()) {
            // NsdManager only exposes the string setter; it encodes values as UTF-8 bytes.
            nsdServiceInfo.setAttribute(attribute.getKey(),
                new String(attribute.getValue(), StandardCharsets.UTF_8));
        }
        return nsdServiceInfo;
    }

    // Certificate identity of the current call, or null on a plaintext call.
    @Nullable
    private static TlsCertificate peerCertificate() {
        return PeerIdentityMapper.fromSession(TlsSessionInterceptor.currentSession());
    }

    /**
     * Applies the server-side trust policy to a streaming peer.
     *
     * <p>A device seen for the first time is pinned silently: the peer may be locked and cannot
     * answer a prompt, and whether it may watch is a separate authorization question. A known id
     * with another fingerprint is rejected; there is no automatic rebind.</p>
     *
     * @return the rejection reason, or null when the peer is trusted
     */
    @Nullable
    private String acceptPeer(@NonNull final TlsCertificate peer) {
        final TlsPinStore pins = TlsPinStore.get();
        final TlsPin pin = pins.find(peer.getDeviceId());
        final PinPolicy.Outcome outcome =
            PinPolicy.decide(peer.getDeviceId(), peer.getSpki(), pin);
        switch (outcome) {
            case MATCH:
                return null;
            case UNKNOWN:
                pins.pin(peer.getDeviceId(), peer.getFingerprint());
                Log.i(mTag, "acceptPeer: new device pinned");
                return null;
            case CHANGED:
                Log.w(mTag, "acceptPeer: peer fingerprint changed, rejecting");
                return PinningTrustManager.REASON_IDENTITY_CHANGED;
            case IDENTITY_MISMATCH:
            default:
                Log.w(mTag, "acceptPeer: peer certificate does not match its id, rejecting");
                return PinningTrustManager.REASON_IDENTITY_MISMATCH;
        }
    }

    /**
     * gRPC service implementation for the media stream and the discovery handshake.
     *
     * <p>Each stream call creates a {@link StreamCallHandler} that joins the shared stream hub and
     * drives one per-client drain thread. The inbound observer only forwards client control
     * messages to the handler; media is pushed out by the drain thread through the handler's
     * transport, so a slow client never blocks gRPC callbacks or the encoder.</p>
     *
     * <p>{@code register} is the unary discovery handshake on the same channel. It delegates to
     * {@link RegisterCallHandler}, which validates, rate-limits, learns the caller and answers
     * with this device's summary; a stopped server answers {@code unavailable} instead of failing
     * the call.</p>
     */
    private final class StreamService extends ScreenStreamServiceGrpc.ScreenStreamServiceImplBase {
        @Override
        public void register(final RegisterRequest request,
            final StreamObserver<RegisterResponse> responseObserver) {
            final RegisterCallHandler handler = mRegisterHandler;
            if (mIsStopped || handler == null) {
                responseObserver.onNext(RegisterResponse.newBuilder()
                    .setProtocolVersion(ScreenStreamProtoMapper.PROTOCOL_VERSION)
                    .setAccepted(false)
                    .setRejectReason(RegisterCallHandler.REASON_UNAVAILABLE)
                    .build());
                responseObserver.onCompleted();
                return;
            }

            final TlsCertificate peer = peerCertificate();
            try {
                handler.handle(request, remoteHost(), peer, response -> {
                    responseObserver.onNext(response);
                    responseObserver.onCompleted();
                });
            } catch (RuntimeException e) {
                Log.e(mTag, "register: handling failed", e);
                responseObserver.onNext(RegisterResponse.newBuilder()
                    .setProtocolVersion(ScreenStreamProtoMapper.PROTOCOL_VERSION)
                    .setAccepted(false)
                    .setRejectReason(RegisterCallHandler.REASON_INVALID_REQUEST)
                    .build());
                responseObserver.onCompleted();
            }
        }

        @Override
        public StreamObserver<ClientMessage> stream(StreamObserver<ServerMessage> responseObserver) {
            if (mIsStopped || mStreamHub == null || mStreamRuntime == null) {
                responseObserver.onCompleted();
                return new NoopClientObserver();
            }
            final TlsCertificate peer = peerCertificate();
            if (!Preferences.get().isTlsAllowPlaintext() && peer == null) {
                responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("peer_certificate_missing")
                    .asRuntimeException());
                return new NoopClientObserver();
            }
            final String rejection = peer == null ? null : acceptPeer(peer);
            if (rejection != null) {
                responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription(rejection)
                    .asRuntimeException());
                return new NoopClientObserver();
            }
            final InetSocketAddress remoteAddress = REMOTE_ADDRESS.get();
            final GrpcMediaTransport transport = new GrpcMediaTransport(responseObserver, mSecure);
            final StreamCallHandler handler = new StreamCallHandler(mStreamHub, mStreamRuntime,
                    transport, mStreamCallListener, remoteAddress, transport::sendPong, peer);
            mStreamCallHandlers.add(handler);
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

            // Terminate the outbound stream so a closed client never hangs waiting for media that
            // will not come. A join failure is surfaced as an error (already logged by the
            // listener when the join was refused); a normal close completes.
            final String joinFailure = handler.getJoinFailure();
            if (joinFailure != null) {
                try {
                    responseObserver.onError(Status.PERMISSION_DENIED
                            .withDescription(joinFailure)
                            .asRuntimeException());
                } catch (RuntimeException ignored) {
                    // the observer was already completed or the client cancelled
                }
            } else {
                try {
                    responseObserver.onCompleted();
                } catch (RuntimeException ignored) {
                    // the observer was already completed or the client cancelled
                }
            }
        }
    }

    /**
     * Forwards client control messages to a handler and closes it when the call ends.
     */
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

    /**
     * Discards every client message; used once the server has stopped.
     */
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
     * Aggregated transport accounting while the server is running.
     *
     * <p>{@code mLock} guards the transport list and the collection timestamp; byte counters are
     * atomics updated from gRPC transport threads. {@link #collect(List, Map)} runs on the main
     * thread, resets per-transport counters and delegates the aggregation to
     * {@link ServerStatsMapper}.</p>
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
        public ServerStats collect(@NonNull final List<SessionSnapshot> sessions,
                                   @NonNull final Map<String, String> addressByClientId) {
            synchronized (mLock) {
                // getAndSet closes the current interval and opens the next one.
                final long startTimestamp = mCollectionTimestamp.getAndSet(SystemClock.elapsedRealtimeNanos());
                final long endTimestamp = mCollectionTimestamp.get();
                final long periodMs = Math.max(1L, (endTimestamp - startTimestamp) / 1_000_000L);
                final List<ServerStatsMapper.AddressTraffic> traffic =
                    new ArrayList<>(mTransports.size());
                for (final TransportProfile transportProfile : mTransports) {
                    traffic.add(new ServerStatsMapper.AddressTraffic(
                        transportProfile.getRemoteAddress().toString(),
                        transportProfile.getAndResetOutboundDataSize()));
                }
                return ServerStatsMapper.map(traffic, sessions, addressByClientId, periodMs);
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
