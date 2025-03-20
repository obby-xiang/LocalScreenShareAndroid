package com.obby.android.localscreenshare.server;

import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;

import com.google.protobuf.Empty;
import com.obby.android.localscreenshare.grpc.screenstream.ScreenFrame;
import com.obby.android.localscreenshare.grpc.screenstream.ScreenStreamServiceGrpc;
import com.obby.android.localscreenshare.support.Preferences;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

public final class LssServer {
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

    public void start() throws IOException {
        mGrpcServer.start();
    }

    public void stop() {
        mGrpcServer.shutdownNow();
        mScreenStreamService.stop();
        mGrpcServerExecutor.shutdownNow();
    }

    public void dispatchScreenFrame(@NonNull final ScreenFrame frame) {
        mScreenStreamService.dispatchScreenFrame(frame);
    }

    private static class ScreenStreamService extends ScreenStreamServiceGrpc.ScreenStreamServiceImplBase {
        @Nullable
        private ScreenFrame mScreenFrame;

        @NonNull
        private final Executor mExecutor;

        @NonNull
        private final List<Pair<ServerCallStreamObserver<ScreenFrame>, Runnable>> mResponseObservers =
            new CopyOnWriteArrayList<>();

        public ScreenStreamService(@NonNull final Executor executor) {
            mExecutor = executor;
        }

        @Override
        public void getScreenStream(Empty request, StreamObserver<ScreenFrame> responseObserver) {
            final ServerCallStreamObserver<ScreenFrame> responseCallObserver =
                (ServerCallStreamObserver<ScreenFrame>) responseObserver;
            final Runnable onReadyHandler = new Runnable() {
                @NonNull
                private final Lock mLock = new ReentrantLock();

                @NonNull
                private final AtomicLong mFrameTimestamp = new AtomicLong(-1L);

                @Override
                public void run() {
                    if (!mLock.tryLock()) {
                        return;
                    }

                    try {
                        Optional.ofNullable(mScreenFrame).ifPresent(screenFrame -> {
                            if (mFrameTimestamp.get() < screenFrame.getTimestamp()) {
                                mFrameTimestamp.set(screenFrame.getTimestamp());
                                responseCallObserver.onNext(screenFrame);
                            }
                        });
                    } finally {
                        mLock.unlock();
                    }
                }
            };
            mResponseObservers.add(Pair.create(responseCallObserver, onReadyHandler));

            responseCallObserver.setOnCloseHandler(
                () -> mResponseObservers.remove(Pair.create(responseCallObserver, onReadyHandler)));
            responseCallObserver.setOnCancelHandler(
                () -> mResponseObservers.remove(Pair.create(responseCallObserver, onReadyHandler)));
            responseCallObserver.setOnReadyHandler(onReadyHandler);

            if (responseCallObserver.isReady()) {
                mExecutor.execute(onReadyHandler);
            }
        }

        public void dispatchScreenFrame(@NonNull final ScreenFrame frame) {
            mScreenFrame = frame;
            mResponseObservers.forEach(pair -> {
                if (pair.first.isReady()) {
                    mExecutor.execute(pair.second);
                }
            });
        }

        public void stop() {
            mScreenFrame = null;
            mResponseObservers.clear();
        }
    }
}
