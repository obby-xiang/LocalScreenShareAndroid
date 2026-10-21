package com.obby.android.localscreenshare.client;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.core.client.CatchUpPolicy;
import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.metrics.RttEstimator;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.ClientState;
import com.obby.android.localscreenshare.grpc.screenstream.ClientMessage;
import com.obby.android.localscreenshare.grpc.screenstream.Hello;
import com.obby.android.localscreenshare.grpc.screenstream.Ping;
import com.obby.android.localscreenshare.grpc.screenstream.RequestKeyframe;
import com.obby.android.localscreenshare.grpc.screenstream.ScreenStreamServiceGrpc;
import com.obby.android.localscreenshare.grpc.screenstream.ServerMessage;
import com.obby.android.localscreenshare.identity.IdentityUnavailableException;
import com.obby.android.localscreenshare.identity.tls.TlsMaterial;
import com.obby.android.localscreenshare.identity.tls.TlsMaterialHolder;
import com.obby.android.localscreenshare.identity.tls.TlsNames;
import com.obby.android.localscreenshare.settings.Preferences;
import com.obby.android.localscreenshare.support.Constants;
import com.obby.android.localscreenshare.trust.PinningTrustManager;
import com.obby.android.localscreenshare.trust.TlsPinStore;
import com.obby.android.localscreenshare.transport.GrpcConstants;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ChannelCredentials;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientStreamTracer;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.TlsChannelCredentials;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.StreamObserver;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * Client endpoint for one remote screen stream over the bidirectional gRPC stream.
 *
 * <p>Each instance owns an OkHttp-backed {@link ManagedChannel}. {@link #start(ClientObserver,
 * ClientMediaReceiver)} opens the stream, announces the client with a HELLO and hands every server
 * message to a dedicated single-thread decode executor that drives the {@link ClientMediaReceiver}.
 * Decoding therefore never runs on the gRPC executor. {@link #requestKeyframe()} asks the server for
 * a random-access point. A decode backlog that persists makes the client <i>catch up</i>: it
 * discards what is queued, flushes the decoder and resumes on a fresh join point, because H.264
 * cannot resume on a P frame whose references were dropped; {@link CatchUpPolicy} owns when that
 * happens. {@link #stop()} shuts the channel and both executors down permanently, so an instance is
 * single-use.</p>
 *
 * <p><b>Threading.</b> {@link #start} and {@link #stop} are expected on the main thread. gRPC
 * transport invokes the response observer on threads of the private {@code lss-grpc-client-*}
 * executor; that observer only enqueues work: video messages onto the {@code lss-client-decode}
 * thread, audio messages onto the {@code lss-client-audio} thread. The two tracks are decoded on
 * separate single threads because a 1080p60 frame occupies the decode thread for a whole frame
 * period, and an audio frame queued behind one starves the playback track. Observer callbacks are
 * always delivered on the main thread. The catch-up decisions run on the decode thread; the only
 * state they share with the transport thread is the catch-up generation, an {@link AtomicInteger}.
 * Writes to the request observer stay on the main thread: the media path posts its key-frame
 * request instead of writing from the decode thread.</p>
 */
@Accessors(prefix = "m")
public final class ScreenShareClient {
    private static final long UPDATE_CLIENT_STATS_INTERVAL_MS = 3000L;

    // Cadence of the round-trip probe. One probe per second is under 40 bytes per second and far
    // below any congestion-visible load.
    private static final long PING_INTERVAL_MS = 1000L;

    // Bounds the decode queue so a catastrophically slow decoder cannot grow it without limit;
    // a rejected message is dropped and the session recovers on the next join point.
    private static final int DECODE_QUEUE_CAPACITY = 256;

    // Bounds the audio queue. Audio is decoded on its own thread so a 1080p60 video frame cannot
    // hold a 21 ms audio frame behind it; the bound is what keeps a stalled audio decoder from
    // growing the queue without limit. Thirty-two frames are about 680 ms of media.
    private static final int AUDIO_QUEUE_CAPACITY = 32;

    // Limits how often a decode-queue overflow asks for a key frame; requests are raised on the
    // transport thread that detected the overflow.
    @NonNull
    private final KeyFrameRequestThrottle mKeyFrameRequestThrottle = new KeyFrameRequestThrottle();

    // Decides when the decode backlog is given up; sampled on the decode thread, one call per
    // decoded message.
    @NonNull
    private final CatchUpPolicy mCatchUpPolicy = new CatchUpPolicy();

    // Orders decode-queue submissions against catch-up episodes. A task records the value it was
    // submitted under; when an episode bumps it, the tasks already queued are discarded instead of
    // decoded. Written on the decode thread, read on the transport thread.
    @NonNull
    private final AtomicInteger mCatchUpGeneration = new AtomicInteger();

    // Moment the current catch-up started, in the metric scope's clock; only valid while
    // mCatchUpPolicy reports an episode.
    private long mCatchUpStartedNs;

    // Lifecycle flag, written on the main thread and read by the transport and decode threads;
    // once set, callbacks become no-ops. Volatile because a stop tears the channel down from the
    // main thread and the transport then reports the cancellation on its own thread: without the
    // write being visible, that late error would be logged as a peer failure.
    private volatile boolean mIsStopped;

    // Non-null only between start() and stop().
    @Nullable
    private ClientProfile mClientProfile;

    // Last sampled value; used to suppress duplicate listener calls.
    @Nullable
    private ClientStats mClientStats;

    // Metric scope of the receiving pipeline; NONE when the preference is off.
    @NonNull
    private final PipelineMetrics mMetrics;

    // Random per-run session id announced in the HELLO; logs print its tail only.
    @Nullable
    private String mClientId;

    // Round-trip estimator fed by the echo messages; only records into mMetrics.
    @NonNull
    private final RttEstimator mRttEstimator;

    // Sends one probe, sweeps the unanswered ones and reschedules itself; runs on the main thread.
    @NonNull
    private final Runnable mPingRunnable = new Runnable() {
        @Override
        public void run() {
            mMainHandler.removeCallbacks(mPingRunnable);
            final StreamObserver<ClientMessage> observer = mRequestObserver;
            if (mIsStopped || observer == null || !mMetrics.isEnabled()) {
                // A disabled scope must stay free of samples and of probe traffic.
                return;
            }
            final int seq = mRttEstimator.beginProbe();
            try {
                observer.onNext(ClientMessage.newBuilder()
                    .setPing(Ping.newBuilder()
                        .setSeq(seq)
                        .setClientUptimeMs(SystemClock.elapsedRealtime())
                        .build())
                    .build());
            } catch (RuntimeException ignored) {
                // The stream is already terminating; the sweep below records the lost sample.
            }
            mRttEstimator.checkTimeouts();
            mMainHandler.postDelayed(mPingRunnable, PING_INTERVAL_MS);
        }
    };

    // Invoked on the main thread by mUpdateClientStatsRunnable.
    @Setter
    @Nullable
    private ClientStatsListener mClientStatsListener;

    private final String mTag = "ScreenShareClient@" + hashCode();

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

    // Dedicated single thread that drives the video session; serializes all video decoding. Typed
    // as a pool executor so the receive probe can sample its queue depth.
    @NonNull
    private final ThreadPoolExecutor mDecodeExecutor = new ThreadPoolExecutor(1, 1, 30L,
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

    // Dedicated single thread for the audio track. Audio used to share the video decode thread,
    // where a 1080p60 frame consumed the whole 16.7 ms budget and every audio frame queued behind
    // it; measured on device, application-level audio starvation correlated with presentation drops
    // at r = 0.94. The audio thread is separate for the same reason the writer thread is: a video
    // decode must never sit between an audio frame and the sink.
    @NonNull
    private final ThreadPoolExecutor mAudioExecutor = new ThreadPoolExecutor(1, 1, 30L,
        TimeUnit.SECONDS, new LinkedBlockingQueue<>(AUDIO_QUEUE_CAPACITY),
        new BasicThreadFactory.Builder().namingPattern("lss-client-audio-%d")
            .wrappedFactory(runnable -> new Thread(runnable) {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
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

            final ClientStats clientStats = mClientProfile.collect();
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
    public ScreenShareClient(@NonNull final String host, final int port) {
        this(host, port, PipelineMetrics.NONE, null);
    }

    /**
     * Creates the gRPC channel and binds the receive probes to {@code metrics}.
     *
     * <p>The scope receives the arrival cadence and relative delay of the wire messages, the decode
     * queue depth and wait, and the number of messages dropped because the decode queue was full.
     * A disabled scope keeps every probe a single volatile read.</p>
     *
     * @param host    remote host address
     * @param port    remote gRPC port
     * @param metrics metric scope of the receiving pipeline; required
     * @throws IllegalArgumentException if {@code metrics} is null
     */
    public ScreenShareClient(@NonNull final String host, final int port,
                             @NonNull final PipelineMetrics metrics) {
        this(host, port, metrics, null);
    }

    /**
     * Creates the gRPC channel and pins the server identity to {@code expectedDeviceId}.
     *
     * <p>The channel verifies the peer against {@link TlsPinStore}: a device that was confirmed
     * before must present the same certificate fingerprint, and a device without a pin is refused
     * here because the confirmation happens before the viewer starts. The authority override is
     * fixed so the self-signed certificate has one hostname to match, whatever address the peer
     * was discovered at.</p>
     *
     * @param host             remote host address
     * @param port             remote gRPC port
     * @param metrics          metric scope of the receiving pipeline; required
     * @param expectedDeviceId device id selected in discovery, or null in tests
     * @throws IllegalArgumentException if {@code metrics} is null
     */
    public ScreenShareClient(@NonNull final String host, final int port,
                             @NonNull final PipelineMetrics metrics,
                             @Nullable final String expectedDeviceId) {
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        mMetrics = metrics;
        mRttEstimator = new RttEstimator(metrics, metrics.getClock());
        final boolean isPlaintext = Preferences.get().isTlsAllowPlaintext();
        ChannelCredentials credentials = InsecureChannelCredentials.create();
        if (!isPlaintext) {
            final TlsChannelCredentials.Builder tls = TlsChannelCredentials.newBuilder()
                .trustManager(new PinningTrustManager(expectedDeviceId, TlsPinStore.get()));
            try {
                // The server requires a client certificate; the identity key signs the handshake.
                tls.keyManager(TlsMaterialHolder.get().material().getKeyManager());
            } catch (IdentityUnavailableException e) {
                Log.w(mTag, "constructor: identity unavailable, the peer will reject the call");
            }
            credentials = tls.build();
        }
        final OkHttpChannelBuilder builder = OkHttpChannelBuilder.forAddress(host, port, credentials)
            .flowControlWindow(GrpcConstants.GRPC_FLOW_CONTROL_WINDOW)
            .maxInboundMessageSize(GrpcConstants.GRPC_MAX_INBOUND_MESSAGE_SIZE)
            .executor(mGrpcClientExecutor)
            .intercept(mClientInterceptor);
        if (!isPlaintext) {
            builder.overrideAuthority(TlsNames.AUTHORITY);
        }
        mGrpcChannel = builder.build();
    }

    /**
     * Opens the bidirectional stream, announces the client and starts the periodic stats sampling.
     *
     * <p>Returns immediately. {@link ClientObserver#onConnected()} is reported on the main thread
     * when the first server message arrives, which means the transport is established; the session's
     * own state listener reports the transition to streaming. A HELLO carries this client's id; the
     * capability set is left empty until runtime probing lands, which the server treats as the
     * H.264 baseline.</p>
     *
     * <p>Call once, on the main thread. The observer is retained until the stream terminates or
     * {@link #stop()} is called, and it is never called after {@code stop()} returns.</p>
     *
     * @param observer receiver of connection-state callbacks
     * @param receiver media session driver; video messages are handed to it on the decode thread and
     *                 audio messages on the audio thread
     */
    public void start(@NonNull final ClientObserver observer,
                      @NonNull final ClientMediaReceiver receiver) {
        mRequestObserver = ScreenStreamServiceGrpc.newStub(mGrpcChannel)
            .stream(new StreamObserver<ServerMessage>() {
                private boolean mIsConnected;

                @Override
                public void onNext(ServerMessage value) {
                    if (mIsStopped) {
                        return;
                    }
                    if (value.getPayloadCase() == ServerMessage.PayloadCase.PONG) {
                        // The echo never enters the decode queue: it measures the path, and waiting
                        // behind frames would turn it into a queue-depth measurement.
                        mRttEstimator.onPong(value.getPong().getSeq());
                        ensureConnected();
                        return;
                    }
                    // One clock read marks the arrival; everything downstream measures from it.
                    final long arrivalNs = mMetrics.nowNs();
                    if (isAudio(value.getPayloadCase())) {
                        // Audio has its own thread: a 1080p60 video frame occupies the decode
                        // thread for about a frame period, and every audio frame queued behind one
                        // left the track starved.
                        try {
                            mAudioExecutor.execute(() -> onAudioTask(receiver, value, arrivalNs));
                        } catch (RejectedExecutionException e) {
                            mMetrics.increment(PipelineMetrics.Counter.AUDIO_DROPPED);
                            Log.w(mTag, "onNext: audio queue full, dropping an audio message");
                        }
                        if (!mIsConnected) {
                            ensureConnected();
                        }
                        return;
                    }
                    final int generation = mCatchUpGeneration.get();
                    final boolean descriptor =
                        value.getPayloadCase() == ServerMessage.PayloadCase.DESCRIPTOR;
                    // Decoding runs on the dedicated decode thread, never on the gRPC executor.
                    try {
                        mDecodeExecutor.execute(
                            () -> onDecodeTask(receiver, value, arrivalNs, generation, descriptor));
                        mMetrics.record(PipelineMetrics.Window.QUEUE_DEPTH,
                            mDecodeExecutor.getQueue().size());
                    } catch (RejectedExecutionException e) {
                        // The dropped message was an encoded access unit: the reference chain now
                        // has a gap, so ask for a fresh join point instead of waiting for the next
                        // periodic key frame.
                        mMetrics.increment(PipelineMetrics.Counter.ARRIVAL_SHEDS);
                        Log.w(mTag, "onNext: decode queue full, dropping a message");
                        requestKeyFrameThrottledFromMediaPath();
                    }
                    if (!mIsConnected) {
                        ensureConnected();
                    }
                }

                /**
                 * Reports the connection once, on the first server message of any kind.
                 */
                private void ensureConnected() {
                    if (mIsConnected) {
                        return;
                    }
                    mIsConnected = true;
                    mMainHandler.post(() -> {
                        if (!mIsStopped) {
                            observer.onConnected();
                        }
                    });
                }

                @Override
                public void onError(Throwable t) {
                    if (mIsStopped) {
                        // A local stop shuts the channel down, which the transport reports as
                        // CANCELLED; that is neither a failure of the peer nor news for the log.
                        return;
                    }
                    // Every termination is logged with its reason. Without it a refused identity, a
                    // server rejection and a lost connection look identical in logcat and in the
                    // UI, which is what made the failure paths unreportable in the field.
                    final StreamFailure failure = StreamFailure.from(t);
                    Log.w(mTag, "onError: stream terminated, " + failure.toLogText());
                    onDisconnected(failure);
                }

                @Override
                public void onCompleted() {
                    Log.i(mTag, "onCompleted: stream completed by the peer");
                    onDisconnected(StreamFailure.completed());
                }

                private void onDisconnected(@NonNull final StreamFailure failure) {
                    mMainHandler.post(() -> {
                        if (!mIsStopped) {
                            observer.onDisconnected(failure);
                        }
                    });
                }
            });

        mClientId = "client-" + UUID.randomUUID();
        final Hello.Builder hello = Hello.newBuilder()
            .setProtocolVersion(ScreenStreamProtoMapper.PROTOCOL_VERSION)
            .setClientId(mClientId)
            .setCapabilities(ScreenStreamProtoMapper.toProtoCapabilities(
                CodecCapabilityProbe.probeClientDecoders()));
        // The discovery identity binds this session to the device entry peers already know. The
        // client id above stays the stream hub's session key, so a reconnect of the same device is
        // still a new session. The id comes from the TLS material, because the server compares it
        // with the certificate of this very handshake and refuses the stream when the two disagree.
        try {
            final TlsMaterial material = TlsMaterialHolder.get().material();
            hello.setDeviceId(material.getDeviceId()).setFingerprint(material.getFingerprint());
        } catch (IdentityUnavailableException e) {
            Log.w(mTag, "start: device identity unavailable, HELLO carries no discovery key");
        }

        mRequestObserver.onNext(ClientMessage.newBuilder()
            .setHello(hello.build())
            .build());

        mClientProfile = new ClientProfile(mMetrics);
        mMainHandler.postDelayed(mUpdateClientStatsRunnable, UPDATE_CLIENT_STATS_INTERVAL_MS);
        if (mMetrics.isEnabled()) {
            mMainHandler.postDelayed(mPingRunnable, PING_INTERVAL_MS);
        }
    }

    /**
     * Returns the tail of the random client id announced in the HELLO, or null before start.
     *
     * <p>Only the last eight characters are meant for logs: the id is a per-run random value, not a
     * device identity, and it is what lets a client log line be correlated with the server's
     * session row.</p>
     */
    @Nullable
    public String getClientIdTail() {
        return ClientSessionKey.tailOf(mClientId);
    }

    /**
     * Asks the server for a random-access point; a no-op before start or after stop.
     *
     * <p>Writes to the request observer, which is not thread-safe, so this runs on the main thread
     * only; the media path reaches it through {@link #requestKeyFrameThrottledFromMediaPath()}.</p>
     */
    public void requestKeyframe() {
        final StreamObserver<ClientMessage> observer = mRequestObserver;
        if (observer == null || mIsStopped) {
            return;
        }
        mMetrics.increment(PipelineMetrics.Counter.KEYFRAME_REQUESTS);
        observer.onNext(ClientMessage.newBuilder()
            .setRequestKeyframe(RequestKeyframe.getDefaultInstance())
            .build());
    }

    /**
     * Raises a key-frame request at most once per throttle interval.
     *
     * <p>Used by the congestion path, which can fire for every message while the queue stays full.</p>
     */
    private void requestKeyFrameThrottled() {
        if (mKeyFrameRequestThrottle.tryAcquire(SystemClock.elapsedRealtime())) {
            requestKeyframe();
        }
    }

    /**
     * Asks for a key frame from the media path, which does not run on the main thread.
     *
     * <p>The request observer is a plain gRPC {@link StreamObserver} and must not be written from
     * two threads at once, while the ping probe and the viewer service both write from the main
     * thread. The request is therefore posted there; the throttle still applies inside the post, so
     * a burst of congestion events collapses into one request and a post that arrives after
     * {@link #stop()} sends nothing.</p>
     */
    private void requestKeyFrameThrottledFromMediaPath() {
        mMainHandler.post(this::requestKeyFrameThrottled);
    }

    /**
     * Returns whether a message belongs to the audio track.
     *
     * <p>Audio messages carry their own descriptor and access units and are applied on the audio
     * thread; everything else stays on the video decode thread.</p>
     */
    private static boolean isAudio(@NonNull final ServerMessage.PayloadCase payload) {
        return payload == ServerMessage.PayloadCase.AUDIO_DESCRIPTOR
            || payload == ServerMessage.PayloadCase.AUDIO_ACCESS_UNIT;
    }

    /**
     * Applies one audio message on the audio thread.
     *
     * <p>Audio is exempt from the catch-up generation: a catch-up discards video access units whose
     * reference chain was abandoned, while an audio frame needs no reference and simply continues
     * the timeline. The receiver contains every audio failure itself, so a broken audio track can
     * never terminate the video stream.</p>
     */
    private void onAudioTask(@NonNull ClientMediaReceiver receiver,
                             @NonNull ServerMessage message, long arrivalNs) {
        if (mIsStopped) {
            return;
        }
        receiver.onServerMessage(message, arrivalNs);
    }

    /**
     * Runs one server message on the decode thread: skips it when a catch-up superseded it,
     * otherwise applies it and samples the backlog.
     *
     * <p>The generation check is what makes a catch-up cheap: the access units already queued
     * belong to the pictures the viewer decided to skip, so they are counted and discarded without
     * deserializing a payload or touching the decoder. Descriptors are exempt, because dropping one
     * would leave the session waiting for an epoch it can no longer learn.</p>
     *
     * <p>After the message is applied, a session that is streaming again ends the episode and its
     * recovery time is recorded. The backlog sample is taken afterwards, so the join point that
     * just ended an episode cannot immediately start another one.</p>
     */
    private void onDecodeTask(@NonNull ClientMediaReceiver receiver,
                              @NonNull ServerMessage message, long arrivalNs, int generation,
                              boolean descriptor) {
        if (mIsStopped) {
            return;
        }
        if (CatchUpPolicy.isSuperseded(generation, mCatchUpGeneration.get(), descriptor)) {
            mMetrics.increment(PipelineMetrics.Counter.CATCH_UP_SHEDS);
            return;
        }

        final long nowNs = mMetrics.nowNs();
        final long queueWaitNs = nowNs - arrivalNs;
        mMetrics.record(PipelineMetrics.Window.QUEUE_WAIT, queueWaitNs);
        receiver.onServerMessage(message, arrivalNs);

        if (mCatchUpPolicy.isCatchingUp()
                && receiver.getSession().getState() == ClientState.STREAMING) {
            finishCatchUp(nowNs);
        }

        final CatchUpPolicy.Decision decision = mCatchUpPolicy.onSample(nowNs,
            mDecodeExecutor.getQueue().size(), queueWaitNs);
        if (decision == CatchUpPolicy.Decision.START_CATCH_UP) {
            startCatchUp(receiver, nowNs);
        } else if (decision == CatchUpPolicy.Decision.CONVERGENCE_FAILED) {
            reportConvergenceFailure();
        }
    }

    /**
     * Records the join point that ended the current catch-up.
     */
    private void finishCatchUp(long nowNs) {
        mCatchUpPolicy.onJoinPoint(nowNs);
        mMetrics.record(PipelineMetrics.Window.CATCH_UP_LATENCY, nowNs - mCatchUpStartedNs);
        Log.i(mTag, String.format("finishCatchUp: recovered in %d ms",
            (nowNs - mCatchUpStartedNs) / 1_000_000L));
    }

    /**
     * Gives up the decode backlog and resumes on a fresh join point.
     *
     * <p>Order matters. The generation is bumped first, so every task already queued becomes a
     * no-op and the queue drains at memory speed instead of decode speed. The session is then
     * flushed and moved to {@code RESYNCING}, which is what keeps the following P frames out of the
     * decoder. The key-frame request goes last, through the main-thread marshaller.</p>
     */
    private void startCatchUp(@NonNull ClientMediaReceiver receiver, long nowNs) {
        mCatchUpGeneration.incrementAndGet();
        mCatchUpStartedNs = nowNs;
        mMetrics.increment(PipelineMetrics.Counter.CATCH_UP_EPISODES);
        mMetrics.setGauge(PipelineMetrics.Gauge.CATCH_UP_REASON,
            mCatchUpPolicy.getLastReason() == CatchUpPolicy.Reason.QUEUE_DEPTH
                ? PipelineMetrics.CATCH_UP_REASON_QUEUE_DEPTH
                : PipelineMetrics.CATCH_UP_REASON_QUEUE_WAIT);

        receiver.getSession().onCatchUp();
        requestKeyFrameThrottledFromMediaPath();
        Log.i(mTag, String.format(
            "startCatchUp: dropped the decode backlog, reason = %s, depth = %d, attempts = %d",
            mCatchUpPolicy.getLastReason(), mDecodeExecutor.getQueue().size(),
            mCatchUpPolicy.getConsecutiveCatchUps()));
    }

    /**
     * Reports that dropping the backlog did not restore real-time playback.
     *
     * <p>Nothing is requested here on purpose: the stream is simply slower than its source, and
     * lowering the server's parameters is a fan-out decision that affects every viewer. The
     * picture keeps playing late until the hold expires and the next episode is attempted.</p>
     */
    private void reportConvergenceFailure() {
        mMetrics.increment(PipelineMetrics.Counter.CONVERGENCE_FAILURES);
        Log.w(mTag, String.format(
            "reportConvergenceFailure: %d catch-ups did not converge, holding off for %d ms",
            mCatchUpPolicy.getConsecutiveCatchUps(),
            CatchUpPolicy.HOLD_NANOS / 1_000_000L));
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
        mMainHandler.removeCallbacks(mPingRunnable);
        mClientProfile = null;
        mClientStats = null;
        mRequestObserver = null;
        mMainHandler.removeCallbacksAndMessages(null);
        mGrpcChannel.shutdownNow();
        mGrpcClientExecutor.shutdownNow();
        mDecodeExecutor.shutdownNow();
        mAudioExecutor.shutdownNow();
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
        private final PipelineMetrics mMetrics;

        @NonNull
        private final AtomicLong mCollectionTimestamp = new AtomicLong(SystemClock.elapsedRealtimeNanos());

        @NonNull
        private final AtomicLong mInboundDataSize = new AtomicLong();

        ClientProfile(@NonNull final PipelineMetrics metrics) {
            mMetrics = metrics;
        }

        public void addInboundDataSize(final long size) {
            mInboundDataSize.addAndGet(size);
        }

        private static long toMillis(final long nanos) {
            return Math.round(nanos / 1_000_000d);
        }

        @NonNull
        public ClientStats collect() {
            synchronized (mLock) {
                // getAndSet closes the current interval and opens the next one.
                final long startTimestamp = mCollectionTimestamp.getAndSet(SystemClock.elapsedRealtimeNanos());
                final long endTimestamp = mCollectionTimestamp.get();
                final long inboundDataSize = mInboundDataSize.getAndSet(0L);
                final long inboundDataRate = Math.round((double) inboundDataSize / (endTimestamp - startTimestamp)
                    * Duration.ofSeconds(1L).toNanos());
                return ClientStats.builder()
                    .inboundDataSize(inboundDataSize)
                    .inboundDataRate(inboundDataRate)
                    .decodeLatencyP95Ms(toMillis(mMetrics.stats(
                        PipelineMetrics.Window.DECODE_LATENCY).getP95()))
                    .presentLateP95Ms(toMillis(mMetrics.stats(
                        PipelineMetrics.Window.PRESENT_LATE).getP95()))
                    .roundTripP95Ms(toMillis(mMetrics.stats(
                        PipelineMetrics.Window.RTT).getP95()))
                    .arrivalStalls(mMetrics.count(PipelineMetrics.Counter.STALLS))
                    .build();
            }
        }
    }
}
