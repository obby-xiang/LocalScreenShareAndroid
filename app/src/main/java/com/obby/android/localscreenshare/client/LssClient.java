package com.obby.android.localscreenshare.client;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.grpc.screenstream.ClientMessage;
import com.obby.android.localscreenshare.grpc.screenstream.Hello;
import com.obby.android.localscreenshare.grpc.screenstream.RequestKeyframe;
import com.obby.android.localscreenshare.grpc.screenstream.ScreenStreamServiceGrpc;
import com.obby.android.localscreenshare.grpc.screenstream.ServerMessage;
import com.obby.android.localscreenshare.support.CodecCapabilityProbe;
import com.obby.android.localscreenshare.support.Constants;
import com.obby.android.localscreenshare.transport.ScreenStreamProtoMapper;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientStreamTracer;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.StreamObserver;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * Client endpoint for one remote screen stream over the bidirectional gRPC stream.
 *
 * <p>Each instance owns an OkHttp-backed {@link ManagedChannel}. {@link #start(LssClientObserver,
 * ClientMediaReceiver)} opens the stream, announces the client with a HELLO and hands every server
 * message to a dedicated single-thread decode executor that drives the {@link ClientMediaReceiver}.
 * Decoding therefore never runs on the gRPC executor. {@link #requestKeyframe()} asks the server for
 * a random-access point. {@link #stop()} shuts the channel and both executors down permanently, so
 * an instance is single-use.</p>
 *
 * <p><b>Threading.</b> {@link #start} and {@link #stop} are expected on the main thread. gRPC
 * transport invokes the response observer on threads of the private {@code lss-grpc-client-*}
 * executor; that observer only enqueues work onto the {@code lss-client-decode} thread. Observer
 * callbacks are always delivered on the main thread.</p>
 */
@Accessors(prefix = "m")
public final class LssClient {
    private static final long UPDATE_CLIENT_STATS_INTERVAL_MS = 3000L;

    // Bounds the decode queue so a catastrophically slow decoder cannot grow it without limit;
    // a rejected message is dropped and the session recovers on the next join point.
    private static final int DECODE_QUEUE_CAPACITY = 256;

    // Lifecycle flag, written and read on the main thread; once set, callbacks become no-ops.
    private boolean mIsStopped;

    // Non-null only between start() and stop().
    @Nullable
    private ClientProfile mClientProfile;

    // Last sampled value; used to suppress duplicate listener calls.
    @Nullable
    private LssClientStats mClientStats;

    // Invoked on the main thread by mUpdateClientStatsRunnable.
    @Setter
    @Nullable
    private LssClientStatsListener mClientStatsListener;

    private final String mTag = "LssClient@" + hashCode();

    // Created in the constructor; shut down permanently by stop().
    @NonNull
    private final ManagedChannel mGrpcChannel;

    // Main-thread delivery for stats and disconnect callbacks; stop() clears its queue.
    @NonNull
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // Handles gRPC transport callbacks only; media decoding never runs here.
    @NonNull
    private final ExecutorService mGrpcClientExecutor = new ThreadPoolExecutor(Constants.CPU_COUNT,
        Constants.CPU_COUNT * 2, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(10), new BasicThreadFactory.Builder()
        .namingPattern("lss-grpc-client-%d")
        .wrappedFactory(runnable -> new Thread(runnable) {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                super.run();
            }
        })
        .build(), new ThreadPoolExecutor.AbortPolicy());

    // Dedicated single thread that drives the media session; serializes all decoding.
    @NonNull
    private final ExecutorService mDecodeExecutor = new ThreadPoolExecutor(1, 1, 30L,
        TimeUnit.SECONDS, new LinkedBlockingQueue<>(DECODE_QUEUE_CAPACITY),
        new BasicThreadFactory.Builder().namingPattern("lss-client-decode-%d")
            .wrappedFactory(runnable -> new Thread(runnable) {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
                    super.run();
                }
            })
            .build(), new ThreadPoolExecutor.AbortPolicy());

    // Outbound side of the bidirectional stream; non-null between start() and stop().
    @Nullable
    private StreamObserver<ClientMessage> mRequestObserver;

    // Accounts inbound wire bytes of every call in the current profile; runs on gRPC transport
    // threads.
    @NonNull
    private final ClientStreamTracer.Factory mClientStreamTracerFactory = new ClientStreamTracer.Factory() {
        @Override
        public ClientStreamTracer newClientStreamTracer(ClientStreamTracer.StreamInfo info, Metadata headers) {
            return new ClientStreamTracer() {
                @Override
                public void inboundWireSize(long bytes) {
                    super.inboundWireSize(bytes);
                    Optional.ofNullable(mClientProfile)
                        .ifPresent(clientProfile -> clientProfile.addInboundDataSize(bytes));
                }
            };
        }
    };

    @SuppressWarnings("FieldCanBeLocal")
    @NonNull
    private final ClientInterceptor mClientInterceptor = new ClientInterceptor() {
        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions, Channel next) {
            return next.newCall(method, callOptions.withStreamTracerFactory(mClientStreamTracerFactory));
        }
    };

    @NonNull
    private final Runnable mUpdateClientStatsRunnable = new Runnable() {
        @Override
        public void run() {
            mMainHandler.removeCallbacks(mUpdateClientStatsRunnable);
            if (mClientProfile == null) {
                return;
            }

            final LssClientStats clientStats = mClientProfile.collect();
            if (Objects.equals(mClientStats, clientStats)) {
                Log.w(mTag, "mUpdateClientStatsRunnable: client stats not changed");
            } else {
                mClientStats = clientStats;
                Log.i(mTag, String.format("mUpdateClientStatsRunnable: client stats changed, clientStats = %s",
                    mClientStats));

                if (mClientStatsListener != null) {
                    mClientStatsListener.onClientStatsChanged(mClientStats);
                }
            }

            mMainHandler.postDelayed(mUpdateClientStatsRunnable, UPDATE_CLIENT_STATS_INTERVAL_MS);
        }
    };

    /**
     * Creates the gRPC channel for {@code host:port}; no socket is opened until {@link #start}.
     *
     * <p>Must be called on the main thread.</p>
     *
     * @param host remote host address
     * @param port remote gRPC port
     */
    public LssClient(@NonNull final String host, final int port) {
        mGrpcChannel = OkHttpChannelBuilder.forAddress(host, port, InsecureChannelCredentials.create())
            .flowControlWindow(Constants.GRPC_FLOW_CONTROL_WINDOW)
            .maxInboundMessageSize(Constants.GRPC_MAX_INBOUND_MESSAGE_SIZE)
            .executor(mGrpcClientExecutor)
            .intercept(mClientInterceptor)
            .build();
    }

    /**
     * Opens the bidirectional stream, announces the client and starts the periodic stats sampling.
     *
     * <p>Returns immediately. {@link LssClientObserver#onConnected()} is reported on the main thread
     * when the first server message arrives, which means the transport is established; the session's
     * own state listener reports the transition to streaming. A HELLO carries this client's id; the
     * capability set is left empty until runtime probing lands, which the server treats as the
     * H.264 baseline.</p>
     *
     * <p>Call once, on the main thread. The observer is retained until the stream terminates or
     * {@link #stop()} is called, and it is never called after {@code stop()} returns.</p>
     *
     * @param observer receiver of connection-state callbacks
     * @param receiver media session driver; every server message is handed to it on the decode thread
     */
    public void start(@NonNull final LssClientObserver observer,
                      @NonNull final ClientMediaReceiver receiver) {
        mRequestObserver = ScreenStreamServiceGrpc.newStub(mGrpcChannel)
            .stream(new StreamObserver<ServerMessage>() {
                private boolean mIsConnected;

                @Override
                public void onNext(ServerMessage value) {
                    if (mIsStopped) {
                        return;
                    }
                    // Decoding runs on the dedicated decode thread, never on the gRPC executor.
                    try {
                        mDecodeExecutor.execute(() -> {
                            if (!mIsStopped) {
                                receiver.onServerMessage(value);
                            }
                        });
                    } catch (RejectedExecutionException e) {
                        Log.w(mTag, "onNext: decode queue full, dropping a message");
                    }
                    if (!mIsConnected) {
                        mIsConnected = true;
                        mMainHandler.post(() -> {
                            if (!mIsStopped) {
                                observer.onConnected();
                            }
                        });
                    }
                }

                @Override
                public void onError(Throwable t) {
                    onDisconnected();
                }

                @Override
                public void onCompleted() {
                    onDisconnected();
                }

                private void onDisconnected() {
                    mMainHandler.post(() -> {
                        if (!mIsStopped) {
                            observer.onDisconnected();
                        }
                    });
                }
            });

        mRequestObserver.onNext(ClientMessage.newBuilder()
            .setHello(Hello.newBuilder()
                .setProtocolVersion(1)
                .setClientId("client-" + UUID.randomUUID())
                .setCapabilities(ScreenStreamProtoMapper.toProtoCapabilities(
                    CodecCapabilityProbe.probeClientDecoders()))
                .build())
            .build());

        mClientProfile = new ClientProfile();
        mMainHandler.postDelayed(mUpdateClientStatsRunnable, UPDATE_CLIENT_STATS_INTERVAL_MS);
    }

    /** Asks the server for a random-access point; a no-op before start or after stop. */
    public void requestKeyframe() {
        final StreamObserver<ClientMessage> observer = mRequestObserver;
        if (observer == null || mIsStopped) {
            return;
        }
        observer.onNext(ClientMessage.newBuilder()
            .setRequestKeyframe(RequestKeyframe.getDefaultInstance())
            .build());
    }

    /**
     * Stops the stream and releases every resource owned by this client.
     *
     * <p>Marks the client stopped first, then clears the stats profile, removes pending main-thread
     * callbacks, shuts the gRPC channel and both executors down. Must be called on the main thread;
     * the instance cannot be restarted afterwards.</p>
     */
    public void stop() {
        mIsStopped = true;
        mClientProfile = null;
        mClientStats = null;
        mRequestObserver = null;
        mMainHandler.removeCallbacksAndMessages(null);
        mGrpcChannel.shutdownNow();
        mGrpcClientExecutor.shutdownNow();
        mDecodeExecutor.shutdownNow();
    }

    /**
     * Running inbound-traffic accounting for one client.
     *
     * <p>{@link #addInboundDataSize(long)} is called from gRPC transport threads; {@link #collect()}
     * runs on the main thread, resets the counter and derives bytes per second from the elapsed
     * monotonic time since the previous collection.</p>
     */
    @Accessors(prefix = "m")
    private static class ClientProfile {
        @NonNull
        private final Object mLock = new Object();

        @NonNull
        private final AtomicLong mCollectionTimestamp = new AtomicLong(SystemClock.elapsedRealtimeNanos());

        @NonNull
        private final AtomicLong mInboundDataSize = new AtomicLong();

        public void addInboundDataSize(final long size) {
            mInboundDataSize.addAndGet(size);
        }

        @NonNull
        public LssClientStats collect() {
            synchronized (mLock) {
                // getAndSet closes the current interval and opens the next one.
                final long startTimestamp = mCollectionTimestamp.getAndSet(SystemClock.elapsedRealtimeNanos());
                final long endTimestamp = mCollectionTimestamp.get();
                final long inboundDataSize = mInboundDataSize.getAndSet(0L);
                final long inboundDataRate = Math.round((double) inboundDataSize / (endTimestamp - startTimestamp)
                    * Duration.ofSeconds(1L).toNanos());
                return LssClientStats.builder()
                    .inboundDataSize(inboundDataSize)
                    .inboundDataRate(inboundDataRate)
                    .build();
            }
        }
    }
}
