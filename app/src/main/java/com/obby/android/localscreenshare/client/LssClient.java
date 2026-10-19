package com.obby.android.localscreenshare.client;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pools;

import com.google.protobuf.Empty;
import com.obby.android.localscreenshare.grpc.screenstream.ScreenFrame;
import com.obby.android.localscreenshare.grpc.screenstream.ScreenStreamServiceGrpc;
import com.obby.android.localscreenshare.support.Constants;
import com.obby.android.localscreenshare.support.Reference;
import com.obby.android.localscreenshare.utils.ThreadUtils;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
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
 * Legacy client endpoint for one remote screen stream over gRPC.
 *
 * <p>Each instance owns an OkHttp-backed {@link ManagedChannel} created by the constructor. The
 * channel is not connected until {@link #start(LssClientObserver)} opens the server-streaming call;
 * {@link #stop()} shuts the channel and the private executor down permanently, so an instance is
 * single-use and cannot be started again after it has been stopped.</p>
 *
 * <p><b>Threading.</b> {@link #start(LssClientObserver)} and {@link #stop()} are expected on the
 * main thread. The gRPC transport invokes the stream observer on threads of the private
 * {@code lss-grpc-client-*} executor, where each JPEG frame is decoded; the decoded bitmap is then
 * handed to the main thread. {@link LssClientObserver} and {@link LssClientStatsListener} callbacks
 * are always delivered on the main thread.</p>
 *
 * <p><b>Frame ownership.</b> A frame is passed to the observer as a {@link Reference}; the observer
 * owns it until it calls {@link Reference#clear()}, which returns the bitmap to a small reuse pool
 * or recycles it. Frames whose timestamp does not increase are dropped before delivery.</p>
 *
 * <p><b>Stats.</b> Inbound wire bytes are accumulated per gRPC call by a stream tracer and sampled
 * every 3 seconds on the main thread; the listener is only invoked when the sampled values
 * change.</p>
 */
@Accessors(prefix = "m")
public final class LssClient {
    private static final long UPDATE_CLIENT_STATS_INTERVAL_MS = 3000L;

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

    // Holds up to four decoded bitmaps for inBitmap reuse; drained by stop().
    @NonNull
    private final Pools.Pool<Bitmap> mScreenFrameCache = new Pools.SynchronizedPool<>(4);

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
        .build(), new ThreadPoolExecutor.CallerRunsPolicy());

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
     * Opens the server-streaming call and starts the periodic stats sampling.
     *
     * <p>Returns immediately. {@link LssClientObserver#onConnected()} is reported on the main thread
     * when the first frame is accepted, immediately before that frame is delivered through
     * {@link LssClientObserver#onScreenFrameReceived(Reference, boolean)}; it does not mean that
     * the transport has finished connecting.</p>
     *
     * <p>Call once, on the main thread. The observer is retained until the stream terminates or
     * {@link #stop()} is called, and it is never called after {@code stop()} returns.</p>
     *
     * @param observer receiver of connection and frame callbacks
     */
    public void start(@NonNull final LssClientObserver observer) {
        ScreenStreamServiceGrpc.newStub(mGrpcChannel)
            .getScreenStream(Empty.getDefaultInstance(), new StreamObserver<>() {
                private boolean mIsConnected;

                private long mScreenFrameTimestamp = -1L;

                @Override
                public void onNext(ScreenFrame value) {
                    // First pass only measures the encoded frame, so the destination bitmap can be
                    // sized before the real decode.
                    final BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;

                    try (final InputStream input = value.getData().newInput()) {
                        BitmapFactory.decodeStream(input, null, options);
                    } catch (IOException e) {
                        return;
                    }

                    // Reuse a pooled bitmap of the same size through inBitmap; a mismatched entry is
                    // recycled and replaced by a fresh destination.
                    final Size size = new Size(options.outWidth, options.outHeight);
                    final Bitmap cachedBitmap = mScreenFrameCache.acquire();
                    if (cachedBitmap == null || cachedBitmap.getWidth() != size.getWidth()
                        || cachedBitmap.getHeight() != size.getHeight()) {
                        Optional.ofNullable(cachedBitmap).ifPresent(Bitmap::recycle);
                        options.inBitmap = Bitmap.createBitmap(size.getWidth(), size.getHeight(),
                            Bitmap.Config.ARGB_8888);
                    } else {
                        options.inBitmap = cachedBitmap;
                    }
                    options.inJustDecodeBounds = false;

                    final Bitmap bitmap;
                    try (final InputStream input = value.getData().newInput()) {
                        bitmap = BitmapFactory.decodeStream(input, null, options);
                        if (bitmap == null) {
                            options.inBitmap.recycle();
                            return;
                        }
                    } catch (IOException e) {
                        options.inBitmap.recycle();
                        return;
                    }

                    // Decoding runs on the gRPC thread; connection state and delivery belong to the
                    // main thread.
                    ThreadUtils.runOnMainThread(() -> {
                        if (mIsStopped) {
                            bitmap.recycle();
                            return;
                        }

                        // The first accepted frame doubles as the connect signal for the observer.
                        if (!mIsConnected) {
                            mIsConnected = true;
                            observer.onConnected();
                        }

                        // Ownership passes to the observer; clear() returns the bitmap to the
                        // cache unless the client was stopped in the meantime.
                        final Reference<Bitmap> reference = new Reference<>(bitmap) {
                            @Override
                            public void clear() {
                                super.clear();
                                if (mIsStopped || !mScreenFrameCache.release(bitmap)) {
                                    bitmap.recycle();
                                }
                            }
                        };
                        // Drop frames that do not advance the timestamp; clearing releases the
                        // bitmap immediately.
                        if (mScreenFrameTimestamp >= value.getTimestamp()) {
                            reference.clear();
                            return;
                        }

                        mScreenFrameTimestamp = value.getTimestamp();
                        observer.onScreenFrameReceived(reference, value.getSecure());
                    });
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
                    // Error and completion share the same notification path; it is suppressed after
                    // a local stop().
                    mMainHandler.post(() -> {
                        if (!mIsStopped) {
                            observer.onDisconnected();
                        }
                    });
                }
            });

        mClientProfile = new ClientProfile();
        mMainHandler.postDelayed(mUpdateClientStatsRunnable, UPDATE_CLIENT_STATS_INTERVAL_MS);
    }

    /**
     * Stops the stream and releases every resource owned by this client.
     *
     * <p>Marks the client stopped first, so frame tasks already queued on the main thread become
     * no-ops, then clears the stats profile, removes pending main-thread callbacks, shuts the gRPC
     * channel and its executor down, and drains the bitmap pool. Must be called on the main thread;
     * the instance cannot be restarted afterwards.</p>
     */
    public void stop() {
        mIsStopped = true;
        mClientProfile = null;
        mClientStats = null;
        mMainHandler.removeCallbacksAndMessages(null);
        mGrpcChannel.shutdownNow();
        mGrpcClientExecutor.shutdownNow();

        // Drain and recycle the pool so no bitmap outlives the client.
        while (true) {
            final Bitmap bitmap = mScreenFrameCache.acquire();
            if (bitmap == null) {
                break;
            }
            bitmap.recycle();
        }
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
