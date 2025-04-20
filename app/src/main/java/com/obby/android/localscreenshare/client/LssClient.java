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

@Accessors(prefix = "m")
public final class LssClient {
    private static final long UPDATE_CLIENT_STATS_INTERVAL_MS = 3000L;

    private boolean mIsStopped;

    @Nullable
    private ClientProfile mClientProfile;

    @Nullable
    private LssClientStats mClientStats;

    @Setter
    @Nullable
    private LssClientStatsListener mClientStatsListener;

    private final String mTag = "LssClient@" + hashCode();

    @NonNull
    private final ManagedChannel mGrpcChannel;

    @NonNull
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @NonNull
    private final Pools.Pool<Bitmap> mScreenFrameCache = new Pools.SynchronizedPool<>(4);

    @NonNull
    private final ExecutorService mGrpcClientExecutor = new ThreadPoolExecutor(Constants.CPU_COUNT,
        Constants.CPU_COUNT * 2, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(200), new BasicThreadFactory.Builder()
        .namingPattern("lss-grpc-client-%d")
        .wrappedFactory(runnable -> new Thread(runnable) {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                super.run();
            }
        })
        .build(), new ThreadPoolExecutor.CallerRunsPolicy());

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

    public LssClient(@NonNull final String host, final int port) {
        mGrpcChannel = OkHttpChannelBuilder.forAddress(host, port, InsecureChannelCredentials.create())
            .flowControlWindow(Constants.GRPC_FLOW_CONTROL_WINDOW)
            .maxInboundMessageSize(Constants.GRPC_MAX_INBOUND_MESSAGE_SIZE)
            .executor(mGrpcClientExecutor)
            .intercept(mClientInterceptor)
            .build();
    }

    public void start(@NonNull final LssClientObserver observer) {
        ScreenStreamServiceGrpc.newStub(mGrpcChannel)
            .getScreenStream(Empty.getDefaultInstance(), new StreamObserver<>() {
                private long mLastScreenFrameTimestamp = -1L;

                @Override
                public void onNext(ScreenFrame value) {
                    final BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;

                    try (final InputStream input = value.getData().newInput()) {
                        BitmapFactory.decodeStream(input, null, options);
                    } catch (IOException e) {
                        return;
                    }

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

                    ThreadUtils.runOnMainThread(() -> {
                        final Reference<Bitmap> reference = new Reference<>(bitmap) {
                            @Override
                            public void clear() {
                                super.clear();
                                if (mIsStopped || !mScreenFrameCache.release(bitmap)) {
                                    bitmap.recycle();
                                }
                            }
                        };
                        if (mLastScreenFrameTimestamp >= value.getTimestamp()) {
                            reference.clear();
                            return;
                        }

                        mLastScreenFrameTimestamp = value.getTimestamp();
                        observer.onScreenFrameReceived(reference);
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

    public void stop() {
        mIsStopped = true;
        mClientProfile = null;
        mClientStats = null;
        mMainHandler.removeCallbacksAndMessages(null);
        mGrpcChannel.shutdownNow();
        mGrpcClientExecutor.shutdownNow();

        while (true) {
            final Bitmap bitmap = mScreenFrameCache.acquire();
            if (bitmap == null) {
                break;
            }
            bitmap.recycle();
        }
    }

    @Accessors(prefix = "m")
    private static class ClientProfile {
        @NonNull
        private final Object mLock = new Object();

        @NonNull
        private final AtomicLong mTimestamp = new AtomicLong(SystemClock.elapsedRealtimeNanos());

        @NonNull
        private final AtomicLong mInboundDataSize = new AtomicLong();

        public void addInboundDataSize(final long size) {
            mInboundDataSize.addAndGet(size);
        }

        @NonNull
        public LssClientStats collect() {
            synchronized (mLock) {
                final long startTimestamp = mTimestamp.getAndSet(SystemClock.elapsedRealtimeNanos());
                final long endTimestamp = mTimestamp.get();
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
